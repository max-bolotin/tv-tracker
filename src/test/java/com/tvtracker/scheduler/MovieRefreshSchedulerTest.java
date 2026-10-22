package com.tvtracker.scheduler;

import com.tvtracker.controller.SseController;
import com.tvtracker.exception.MovieNotFoundException;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.storage.JsonStorageService;
import com.tvtracker.storage.MovieStorageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
        MovieEntry fresh = movie(1L, null);
        when(showStorage.listUserIds()).thenReturn(List.of("alice"));
        when(movieStorage.load("alice")).thenReturn(List.of(existing));
        when(metadata.fetchMovieDetails(1L, true)).thenReturn(fresh);
        stubUpdateMutatesEntry();

        scheduler.doRefresh();

        assertThat(existing.imdbRating).isEqualTo("8.1");
        assertThat(existing.rottenTomatoesScore).isEqualTo("91%");
        assertThat(existing.lastRefreshedAt).isEqualTo(NOW);
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
        when(metadata.fetchMovieDetails(2L, false)).thenReturn(movie(2L, "2026-10-01"));
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
        when(metadata.fetchMovieDetails(7L, true)).thenReturn(movie(7L, "2026-10-01"));
        stubUpdateMutatesEntry();

        scheduler.doRefresh();

        verify(metadata).fetchMovieDetails(7L, true);
        verify(movieStorage).update(eq("alice"), eq("first"), any(), eq(false));
        verify(movieStorage).update(eq("bob"), eq("second"), any(), eq(false));
        verify(sse, org.mockito.Mockito.times(2)).broadcastMoviesChanged();
    }

    @Test
    void limitsOmdbEnrichmentRequestsPerRun() throws Exception {
        MovieEntry first = movie(11L, null);
        first.id = "first";
        MovieEntry second = movie(12L, null);
        second.id = "second";
        when(showStorage.listUserIds()).thenReturn(List.of("alice"));
        when(movieStorage.load("alice")).thenReturn(List.of(first, second));
        when(metadata.fetchMovieDetails(11L, true)).thenReturn(movie(11L, "2026-10-01"));
        when(metadata.fetchMovieDetails(12L, false)).thenReturn(movie(12L, "2026-10-01"));
        stubUpdateMutatesEntry();

        scheduler.doRefresh();

        verify(metadata).fetchMovieDetails(11L, true);
        verify(metadata).fetchMovieDetails(12L, false);
        verify(sse).broadcastMoviesChanged();
    }

    private void stubUpdateMutatesEntry() throws Exception {
        doAnswer(invocation -> {
            MovieEntry current = findMovie(invocation.getArgument(0), invocation.getArgument(1));
            ((java.util.function.Consumer<MovieEntry>) invocation.getArgument(2)).accept(current);
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
