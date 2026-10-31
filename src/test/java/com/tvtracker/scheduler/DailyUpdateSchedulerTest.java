package com.tvtracker.scheduler;

import com.tvtracker.model.Actor;
import com.tvtracker.model.Episode;
import com.tvtracker.model.Season;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.storage.JsonStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DailyUpdateSchedulerTest {

    @Mock
    JsonStorageService storage;

    @Mock
    MetadataService metadata;

    DailyUpdateScheduler scheduler;

    @BeforeEach
    void setup() {
        scheduler = new DailyUpdateScheduler(storage, metadata, 7, 21, 1000L);
    }

    private TrackedShow makeShow(String title, List<Actor> cast) {
        TrackedShow s = new TrackedShow();
        s.title = title;
        s.cast = cast;
        return s;
    }

    @Test
    void freshCastNull_keepsExistingCast() throws Exception {
        TrackedShow existing = makeShow("E1", new ArrayList<>(List.of(new Actor("A", null))));
        TrackedShow fresh = makeShow("E1", null);

        when(storage.listUserIds()).thenReturn(List.of("u1"));
        when(storage.loadAll("u1")).thenReturn(new ArrayList<>(List.of(existing)));
        when(metadata.fetchDetails(existing.tmdbId, existing.tvmazeId, false)).thenReturn(fresh);

        scheduler.doCheck();

        assertThat(existing.cast).isNotNull().hasSize(1);
        verify(storage).saveAll(eq("u1"), anyList());
    }

    @Test
    void freshEmpty_existingNonEmpty_keepsExisting() throws Exception {
        TrackedShow existing = makeShow("E2", new ArrayList<>(List.of(new Actor("B", null))));
        TrackedShow fresh = makeShow("E2", new ArrayList<>());

        when(storage.listUserIds()).thenReturn(List.of("u2"));
        when(storage.loadAll("u2")).thenReturn(new ArrayList<>(List.of(existing)));
        when(metadata.fetchDetails(existing.tmdbId, existing.tvmazeId, false)).thenReturn(fresh);

        scheduler.doCheck();

        assertThat(existing.cast).hasSize(1);
        verify(storage).saveAll(eq("u2"), anyList());
    }

    @Test
    void freshEmpty_existingNull_assignsEmpty() throws Exception {
        TrackedShow existing = makeShow("E3", null);
        TrackedShow fresh = makeShow("E3", new ArrayList<>());

        when(storage.listUserIds()).thenReturn(List.of("u3"));
        when(storage.loadAll("u3")).thenReturn(new ArrayList<>(List.of(existing)));
        when(metadata.fetchDetails(existing.tmdbId, existing.tvmazeId, false)).thenReturn(fresh);

        scheduler.doCheck();

        assertThat(existing.cast).isNotNull().isEmpty();
        verify(storage).saveAll(eq("u3"), anyList());
    }

    @Test
    void freshNonEmpty_replacesExisting() throws Exception {
        TrackedShow existing = makeShow("E4", new ArrayList<>(List.of(new Actor("C", null))));
        TrackedShow fresh = makeShow("E4", new ArrayList<>(List.of(new Actor("D", null))));

        when(storage.listUserIds()).thenReturn(List.of("u4"));
        when(storage.loadAll("u4")).thenReturn(new ArrayList<>(List.of(existing)));
        when(metadata.fetchDetails(existing.tmdbId, existing.tvmazeId, false)).thenReturn(fresh);

        scheduler.doCheck();

        assertThat(existing.cast).hasSize(1);
        assertThat(existing.cast.getFirst().name).isEqualTo("D");
        verify(storage).saveAll(eq("u4"), anyList());
    }

    @Test
    void dormantShowWithoutRatingsRemainsAnOmdbCandidate() throws Exception {
        TrackedShow show = makeShow("Dormant unrated show", new ArrayList<>());
        show.tmdbId = 42L;
        show.imdbId = "tt1234567";
        show.ratingLastFetched = LocalDate.now().minusDays(22).toString();
        show.seasons = new ArrayList<>(List.of(new Season(1)));
        show.seasons.getFirst().episodes = new ArrayList<>(
                List.of(new Episode(1, "Finale", LocalDate.now().minusMonths(7).toString())));
        TrackedShow fresh = makeShow("Dormant unrated show", new ArrayList<>());

        when(storage.listUserIds()).thenReturn(List.of("u5"));
        when(storage.loadAll("u5")).thenReturn(new ArrayList<>(List.of(show)));
        when(metadata.fetchDetails(42L, null, false)).thenReturn(fresh);
        when(metadata.isOmdbConfigured()).thenReturn(true);

        scheduler.doCheck();

        verify(metadata).enrichRatings(show, true);
    }

    @Test
    void unratedShowWithRecentFetchDateIsSelected() throws Exception {
        TrackedShow show = show("Unrated", "tmdb:42");
        show.imdbRating = null;
        show.ratingLastFetched = LocalDate.now().toString();
        runRatingPass(List.of(show));

        verify(metadata).enrichRatings(show, true);
    }

    @Test
    void unratedShowWithFutureFirstEpisodeIsSkipped() throws Exception {
        TrackedShow show = show("Upcoming", "tmdb:43");
        show.seasons = new ArrayList<>(List.of(new Season(1)));
        show.seasons.getFirst().episodes = new ArrayList<>(
                List.of(new Episode(1, "Pilot", LocalDate.now().plusDays(30).toString())));
        runRatingPass(List.of(show));

        verify(metadata, never()).enrichRatings(show, true);
    }

    @Test
    void futureFirstEpisodeFromRefreshedDetailsIsSkipped() throws Exception {
        TrackedShow show = show("Upcoming", "tmdb:47");
        show.seasons = new ArrayList<>(List.of(new Season(1)));
        show.seasons.getFirst().episodes = new ArrayList<>(List.of(new Episode(1, "Pilot", null)));
        TrackedShow fresh = new TrackedShow();
        fresh.seasons = new ArrayList<>(List.of(new Season(1)));
        fresh.seasons.getFirst().episodes = new ArrayList<>(List.of(
                new Episode(1, "Pilot", LocalDate.now().plusDays(30).toString())));
        when(storage.listUserIds()).thenReturn(List.of("fresh-date-user"));
        when(storage.loadAll("fresh-date-user")).thenReturn(new ArrayList<>(List.of(show)));
        when(metadata.fetchDetails(47L, null, false)).thenReturn(fresh);
        when(metadata.isOmdbConfigured()).thenReturn(true);

        scheduler.doCheck();

        verify(metadata, never()).enrichRatings(show, true);
    }

    @Test
    void ratedShowsStillUseTheRandomizedAgeRule() throws Exception {
        TrackedShow stale = show("Stale rating", "tmdb:44");
        stale.imdbRating = "8.0";
        stale.ratingsUpdatedAt = LocalDate.now().minusDays(22).toString();
        TrackedShow recent = show("Recent rating", "tmdb:45");
        recent.imdbRating = "8.0";
        recent.ratingsUpdatedAt = LocalDate.now().minusDays(1).toString();
        runRatingPass(List.of(stale, recent));

        verify(metadata).enrichRatings(stale, true);
        verify(metadata, never()).enrichRatings(recent, true);
    }

    @Test
    void sameUnratedShowIsOnlySelectedOnceAcrossUsers() throws Exception {
        TrackedShow firstUserCopy = show("Shared", "tmdb:46");
        TrackedShow secondUserCopy = show("Shared", "tmdb:46");
        when(storage.listUserIds()).thenReturn(List.of("u6", "u7"));
        when(storage.loadAll("u6")).thenReturn(new ArrayList<>(List.of(firstUserCopy)));
        when(storage.loadAll("u7")).thenReturn(new ArrayList<>(List.of(secondUserCopy)));
        when(metadata.fetchDetails(46L, null, false)).thenReturn(new TrackedShow());
        when(metadata.isOmdbConfigured()).thenReturn(true);

        scheduler.doCheck();

        verify(metadata, times(1)).enrichRatings(any(TrackedShow.class), eq(true));
    }

    private TrackedShow show(String title, String key) {
        TrackedShow show = makeShow(title, new ArrayList<>());
        show.tmdbId = Long.parseLong(key.substring("tmdb:".length()));
        show.imdbId = "tt1234567";
        return show;
    }

    private void runRatingPass(List<TrackedShow> shows) throws Exception {
        when(storage.listUserIds()).thenReturn(List.of("rating-user"));
        when(storage.loadAll("rating-user")).thenReturn(new ArrayList<>(shows));
        for (TrackedShow show : shows) {
            when(metadata.fetchDetails(show.tmdbId, show.tvmazeId, false))
                    .thenReturn(new TrackedShow());
        }
        when(metadata.isOmdbConfigured()).thenReturn(true);
        scheduler.doCheck();
    }
}
