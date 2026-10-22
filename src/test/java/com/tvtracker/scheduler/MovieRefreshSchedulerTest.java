package com.tvtracker.scheduler;

import com.tvtracker.controller.SseController;
import com.tvtracker.exception.MovieNotFoundException;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.provider.MetadataService.MovieMetadataResult;
import com.tvtracker.storage.JsonStorageService;
import com.tvtracker.storage.MovieStorageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MovieRefreshSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Berlin"));

    private JsonStorageService showStorage;
    private MovieStorageService movieStorage;
    private MetadataService metadata;
    private SseController sse;
    private MovieRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        showStorage = mock(JsonStorageService.class);
        movieStorage = mock(MovieStorageService.class);
        metadata = mock(MetadataService.class);
        sse = mock(SseController.class);
        scheduler = new MovieRefreshScheduler(
                showStorage, movieStorage, metadata, sse, CLOCK,
                Duration.ofHours(24), Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(90), 1);
    }

    @Test
    void selectsUnreleasedAndMissingDatesEvery24Hours() {
        MovieEntry unreleased = movie(1L, null);
        assertThat(scheduler.shouldRefresh(unreleased, NOW)).isTrue();
        unreleased.lastRefreshedAt = NOW.minus(Duration.ofHours(23));
        assertThat(scheduler.shouldRefresh(unreleased, NOW)).isFalse();
        unreleased.lastRefreshedAt = NOW.minus(Duration.ofHours(25));
        assertThat(scheduler.shouldRefresh(unreleased, NOW)).isTrue();
    }

    @Test
    void selectsReleasedMoviesWeeklyForFirst90DaysThenMonthly() {
        MovieEntry recent = movie(1L, LocalDate.now(CLOCK).minusDays(30).toString());
        recent.lastRefreshedAt = NOW.minus(Duration.ofDays(8));
        assertThat(scheduler.shouldRefresh(recent, NOW)).isTrue();
        recent.lastRefreshedAt = NOW.minus(Duration.ofDays(6));
        assertThat(scheduler.shouldRefresh(recent, NOW)).isFalse();

        MovieEntry older = movie(2L, LocalDate.now(CLOCK).minusDays(120).toString());
        older.lastRefreshedAt = NOW.minus(Duration.ofDays(29));
        assertThat(scheduler.shouldRefresh(older, NOW)).isFalse();
        older.lastRefreshedAt = NOW.minus(Duration.ofDays(31));
        assertThat(scheduler.shouldRefresh(older, NOW)).isTrue();
    }

    @Test
    void keepsExistingRatingsWhenFetchedRatingsAreNull() throws Exception {
        MovieEntry existing = movie(1L, null);
        existing.id = "one";
        existing.imdbRating = "8.1";
        existing.rottenTomatoesScore = "91%";
        existing.imdbId = "tt1234567";
        existing.poster = "/existing.jpg";
        existing.cast = new java.util.ArrayList<>(List.of(new com.tvtracker.model.Actor("Actor", null)));
        MovieEntry fresh = movie(1L, null);
        fresh.imdbId = null;
        fresh.poster = null;
        fresh.cast = new java.util.ArrayList<>();
        when(showStorage.listUserIds()).thenReturn(List.of("alice"));
        when(movieStorage.load("alice")).thenReturn(List.of(existing));
        when(metadata.fetchMovieDetails(1L, true)).thenReturn(new MovieMetadataResult(fresh, false));
        when(metadata.isMovieOmdbEnrichmentApplicable(fresh)).thenReturn(true);
        stubUpdateMutatesEntry();

        scheduler.doRefresh();

        assertThat(existing.imdbRating).isEqualTo("8.1");
        assertThat(existing.rottenTomatoesScore).isEqualTo("91%");
        assertThat(existing.lastRefreshedAt).isNull();
        assertThat(existing.imdbId).isEqualTo("tt1234567");
        assertThat(existing.poster).isEqualTo("/existing.jpg");
        assertThat(existing.cast).hasSize(1);
    }

    @Test
    void isolatesMovieFailuresAndNeverDeletesWhenTmdbReturnsNotFound() throws Exception {
        MovieEntry missing = movie(1L, null);
        missing.id = "missing";
        MovieEntry healthy = movie(2L, null);
        healthy.id = "healthy";
        when(showStorage.listUserIds()).thenReturn(List.of("alice"));
        when(movieStorage.load("alice")).thenReturn(List.of(missing, healthy));
        when(metadata.fetchMovieDetails(1L, true)).thenThrow(new MovieNotFoundException("1"));
        MovieEntry fresh = movie(2L, "2026-10-01");
        when(metadata.fetchMovieDetails(2L, true)).thenReturn(new MovieMetadataResult(fresh, true));
        when(metadata.isMovieOmdbEnrichmentApplicable(fresh)).thenReturn(true);
        stubUpdateMutatesEntry();

        scheduler.doRefresh();

        verify(movieStorage, never()).delete(any(), any());
        verify(movieStorage).update(eq("alice"), eq("healthy"), any(), eq(false));
        verify(movieStorage, never()).update(eq("alice"), eq("missing"), any(), anyBoolean());
        verify(sse).broadcastMoviesChanged();
    }

    @Test
    void cachesTmdbFetchAcrossUsersAndBroadcastsOncePerChangedUser() throws Exception {
        MovieEntry first = movie(7L, null);
        first.id = "first";
        MovieEntry second = movie(7L, null);
        second.id = "second";
        when(showStorage.listUserIds()).thenReturn(List.of("alice", "bob"));
        when(movieStorage.load("alice")).thenReturn(List.of(first));
        when(movieStorage.load("bob")).thenReturn(List.of(second));
        MovieEntry fresh = movie(7L, "2026-10-01");
        when(metadata.fetchMovieDetails(7L, true)).thenReturn(new MovieMetadataResult(fresh, true));
        when(metadata.isMovieOmdbEnrichmentApplicable(fresh)).thenReturn(true);
        stubUpdateMutatesEntry();

        scheduler.doRefresh();

        verify(metadata).fetchMovieDetails(7L, true);
        verify(movieStorage).update(eq("alice"), eq("first"), any(), eq(false));
        verify(movieStorage).update(eq("bob"), eq("second"), any(), eq(false));
        verify(sse).broadcastMoviesChanged();
    }

    @Test
    void limitsOmdbEnrichmentRequestsPerRun() throws Exception {
        MovieEntry first = movie(11L, null);
        first.id = "first";
        MovieEntry second = movie(12L, null);
        second.id = "second";
        when(showStorage.listUserIds()).thenReturn(List.of("alice"));
        when(movieStorage.load("alice")).thenReturn(List.of(first, second));
        MovieEntry freshFirst = movie(11L, "2026-10-01");
        MovieEntry freshSecond = movie(12L, "2026-10-01");
        when(metadata.fetchMovieDetails(11L, true)).thenReturn(new MovieMetadataResult(freshFirst, true));
        when(metadata.fetchMovieDetails(12L, false)).thenReturn(new MovieMetadataResult(freshSecond, false));
        when(metadata.isMovieOmdbEnrichmentApplicable(freshFirst)).thenReturn(true);
        when(metadata.isMovieOmdbEnrichmentApplicable(freshSecond)).thenReturn(true);
        stubUpdateMutatesEntry();

        scheduler.doRefresh();

        verify(metadata).fetchMovieDetails(11L, true);
        verify(metadata).fetchMovieDetails(12L, false);
        verify(sse).broadcastMoviesChanged();
    }

    private void stubUpdateMutatesEntry() throws Exception {
        doAnswer(invocation -> {
            MovieEntry current = findMovie(invocation.getArgument(0), invocation.getArgument(1));
            ((Consumer<MovieEntry>) invocation.getArgument(2)).accept(current);
            return current;
        }).when(movieStorage).update(any(), any(), any(), eq(false));
    }

    private MovieEntry findMovie(String userId, String id) throws Exception {
        return movieStorage.load(userId).stream().filter(movie -> id.equals(movie.id)).findFirst().orElseThrow();
    }

    private static MovieEntry movie(long tmdbId, String releaseDate) {
        MovieEntry movie = new MovieEntry();
        movie.tmdbId = tmdbId;
        movie.releaseDate = releaseDate;
        return movie;
    }
}
