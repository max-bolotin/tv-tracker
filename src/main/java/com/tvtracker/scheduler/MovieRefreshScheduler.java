package com.tvtracker.scheduler;

import com.tvtracker.controller.SseController;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.provider.MovieMetadataMerger;
import com.tvtracker.storage.JsonStorageService;
import com.tvtracker.storage.MovieStorageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.movies.refresh.enabled", havingValue = "true", matchIfMissing = true)
public class MovieRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(MovieRefreshScheduler.class);

    private final JsonStorageService showStorage;
    private final MovieStorageService movieStorage;
    private final MetadataService metadata;
    private final SseController sse;
    private final Clock clock;
    private final Duration unreleasedInterval;
    private final Duration recentRatingsInterval;
    private final Duration oldRatingsInterval;
    private final Duration recentPeriod;
    private final int omdbMaxCallsPerRun;

    public MovieRefreshScheduler(
            JsonStorageService showStorage,
            MovieStorageService movieStorage,
            MetadataService metadata,
            SseController sse,
            Clock clock,
            @Value("${app.movies.refresh.unreleased-interval:PT24H}") Duration unreleasedInterval,
            @Value("${app.movies.refresh.ratings-recent-interval:P7D}") Duration recentRatingsInterval,
            @Value("${app.movies.refresh.ratings-old-interval:P30D}") Duration oldRatingsInterval,
            @Value("${app.movies.refresh.recent-period:P90D}") Duration recentPeriod,
            @Value("${app.movies.refresh.omdb-max-calls-per-run:100}") int omdbMaxCallsPerRun) {
        this.showStorage = showStorage;
        this.movieStorage = movieStorage;
        this.metadata = metadata;
        this.sse = sse;
        this.clock = clock;
        this.unreleasedInterval = unreleasedInterval;
        this.recentRatingsInterval = recentRatingsInterval;
        this.oldRatingsInterval = oldRatingsInterval;
        this.recentPeriod = recentPeriod;
        this.omdbMaxCallsPerRun = omdbMaxCallsPerRun;
    }

    @Scheduled(cron = "${app.movies.refresh.cron:0 30 3 * * *}")
    public void refreshMovies() {
        try {
            doRefresh();
        } catch (Exception e) {
            log.error("Movie refresh run failed: {}", e.getMessage(), e);
        }
    }

    public void doRefresh() {
        Instant now = clock.instant();
        Map<Long, List<MovieReference>> referencesByTmdbId = new LinkedHashMap<>();
        LinkedHashSet<String> userIds = new LinkedHashSet<>(showStorage.listUserIds());
        try {
            userIds.addAll(movieStorage.listUserIds());
        } catch (Exception e) {
            log.warn("Movie refresh could not list movie storage users: {}", e.getMessage(), e);
        }
        for (String userId : userIds) {
            try {
                for (MovieEntry movie : movieStorage.load(userId)) {
                    if (movie.tmdbId != null && shouldRefresh(movie, now)) {
                        referencesByTmdbId.computeIfAbsent(movie.tmdbId, ignored -> new ArrayList<>())
                                .add(new MovieReference(userId, movie.id, movie.releaseDate));
                    }
                }
            } catch (Exception e) {
                log.warn("Movie refresh could not load movies for user {}: {}", userId, e.getMessage(), e);
            }
        }

        boolean changed = false;
        int omdbCalls = 0;
        int refreshedMovies = 0;
        for (Map.Entry<Long, List<MovieReference>> entry : referencesByTmdbId.entrySet()) {
            long tmdbId = entry.getKey();
            MetadataService.MovieMetadataResult result;
            try {
                LocalDate today = LocalDate.now(clock);
                boolean released = entry.getValue().stream()
                        .anyMatch(reference -> isReleased(reference.releaseDate(), today));
                boolean enrichWithOmdb = released && omdbCalls < omdbMaxCallsPerRun;
                result = metadata.fetchMovieDetailsFresh(tmdbId, enrichWithOmdb);
                if (result.omdbRequestAttempted()) {
                    omdbCalls++;
                }
                refreshedMovies++;
            } catch (Exception e) {
                log.warn("Movie refresh failed for TMDB id {}: {}", tmdbId, e.getMessage());
                continue;
            }

            for (MovieReference reference : entry.getValue()) {
                try {
                    movieStorage.update(reference.userId(), reference.movieId(), current -> {
                        MovieMetadataMerger.merge(current, result.movie());
                        if (result.ratingsRefreshed()) {
                            current.lastRefreshedAt = now;
                        }
                    }, false);
                    changed = true;
                } catch (Exception e) {
                    log.warn("Movie refresh failed applying TMDB id {} to user {} movie {}: {}",
                            tmdbId, reference.userId(), reference.movieId(), e.getMessage());
                }
            }
        }

        if (changed) {
            sse.broadcastMoviesChanged();
        }
        log.info("Movie refresh completed: {} unique movies, changes applied: {}",
                refreshedMovies, changed);
    }

    boolean shouldRefresh(MovieEntry movie, Instant now) {
        LocalDate today = LocalDate.now(clock);
        LocalDate releaseDate = parseReleaseDate(movie.releaseDate);
        Duration interval;
        if (releaseDate == null || releaseDate.isAfter(today)) {
            interval = unreleasedInterval;
        } else {
            Instant releaseInstant = releaseDate.atStartOfDay(clock.getZone()).toInstant();
            interval = Duration.between(releaseInstant, now).compareTo(recentPeriod) <= 0
                    ? recentRatingsInterval
                    : oldRatingsInterval;
        }

        return movie.lastRefreshedAt == null || movie.lastRefreshedAt.isBefore(now.minus(interval));
    }

    private static LocalDate parseReleaseDate(String releaseDate) {
        if (releaseDate == null || releaseDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(releaseDate);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isReleased(String releaseDate, LocalDate today) {
        LocalDate date = parseReleaseDate(releaseDate);
        return date != null && !date.isAfter(today);
    }

    private record MovieReference(String userId, String movieId, String releaseDate) {}
}
