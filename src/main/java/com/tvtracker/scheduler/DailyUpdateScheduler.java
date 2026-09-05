package com.tvtracker.scheduler;

import com.tvtracker.model.TrackedShow;
import com.tvtracker.model.WatchStatus;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.storage.JsonStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class DailyUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyUpdateScheduler.class);

    private final JsonStorageService storage;
    private final MetadataService metadata;

    public DailyUpdateScheduler(JsonStorageService storage, MetadataService metadata) {
        this.storage = storage;
        this.metadata = metadata;
    }

    @Scheduled(cron = "${app.scheduler.cron}")
    public void checkForNewEpisodes() {
        log.debug("Running daily episode update check...");
        doCheck();
    }

    /** Called by the manual refresh endpoint — same logic, no cron restriction. */
    public void doCheck() {
        // default behavior (used by scheduler) operates on the default user file
        doCheck("default");
    }

    /** Refresh check for a specific user. */
    public void doCheck(String userId) {
        try {
            List<TrackedShow> shows = storage.loadAll(userId);
            log.debug("Daily update check for user {}: totalShows={}", userId, shows.size());

            Set<Long> updatedTmdb = Set.copyOf(metadata.fetchRecentlyUpdatedTmdbIds());
            Set<Long> updatedTvmaze = Set.copyOf(metadata.fetchRecentlyUpdatedTvmazeIds());

            log.debug("Updated TMDB ids count: {}, TVMaze ids count: {}", updatedTmdb.size(), updatedTvmaze.size());

            for (TrackedShow show : shows) {
                // Always refresh all shows for a full refresh per user request
                boolean matchTmdb = show.tmdbId != null && updatedTmdb.contains(show.tmdbId);
                boolean matchTvmaze = show.tvmazeId != null && updatedTvmaze.contains(show.tvmazeId);

                // If the stored show has empty seasons (placeholders), note it for logs
                boolean hasEmptySeason = show.seasons != null && show.seasons.stream().anyMatch(s -> s.episodes == null || s.episodes.isEmpty());
                if (hasEmptySeason) {
                    log.debug("Checking show '{}' has empty seasons — will refresh", show.title);
                }

                log.debug("Checking show '{}' (tmdbId={}, tvmazeId={}) matchTmdb={} matchTvmaze={} hasEmptySeason={}", show.title, show.tmdbId, show.tvmazeId, matchTmdb, matchTvmaze, hasEmptySeason);

                try {
                    TrackedShow fresh = metadata.fetchDetails(show.tmdbId, show.tvmazeId);
                    boolean hasNewEpisodes = mergeNewEpisodes(show, fresh);
                    if (hasNewEpisodes) {
                        show.watchStatus = WatchStatus.WATCHING_NOW;
                        log.debug("Show '{}' moved to WATCHING_NOW (new episodes added)", show.title);
                    } else {
                        log.debug("Show '{}' was updated but has no new episodes — keeping UP_TO_DATE", show.title);
                    }
                    storage.save(userId, show);
                } catch (Exception e) {
                    log.warn("Failed to refresh show '{}': {}", show.title, e.getMessage());
                }
            }

            // Heal any WATCHING_NOW shows where all episodes are already watched
            // (e.g. stuck there from a previous bug or an empty-season false-positive)
            for (TrackedShow show : shows) {
                if (show.watchStatus != WatchStatus.WATCHING_NOW) continue;
                WatchStatus before = show.watchStatus;
                show.recalculateStatus();
                if (show.watchStatus != before) {
                    storage.save(userId, show);
                    log.debug("Show '{}' healed: {} → {}", show.title, before, show.watchStatus);
                }
            }
        } catch (Exception e) {
            log.error("Daily update check failed for user {}", userId, e);
        }
    }

    /** Merges fresh metadata into existing show. Returns true if any new episodes were added. */
    private boolean mergeNewEpisodes(TrackedShow existing, TrackedShow fresh) {
        boolean addedAny = false;
        for (var freshSeason : fresh.seasons) {
        // skip season number 0
        if (freshSeason.number == 0) {
            log.debug("mergeNewEpisodes: skipping season number 0 for show='{}'", existing.title);
            continue;
        }
        var existingSeason = existing.seasons.stream()
                .filter(s -> s.number == freshSeason.number).findFirst();
        if (existingSeason.isEmpty()) {
            // only add seasons that contain episodes (and skip episode number 0)
            if (freshSeason.episodes != null && !freshSeason.episodes.isEmpty()) {
                var copy = new com.tvtracker.model.Season(freshSeason.number);
                copy.episodes = new java.util.ArrayList<>();
                for (var ep : freshSeason.episodes) {
                    if (ep.number == 0) continue;
                    copy.episodes.add(ep);
                }
                if (!copy.episodes.isEmpty()) {
                    existing.seasons.add(copy);
                    addedAny = true;
                }
            }
        } else if (freshSeason.episodes != null && !freshSeason.episodes.isEmpty()) {
            var es = existingSeason.get();
            for (var freshEp : freshSeason.episodes) {
                if (freshEp.number == 0) continue;
                boolean alreadyExists = es.episodes.stream().anyMatch(e -> e.number == freshEp.number);
                if (!alreadyExists) {
                    es.episodes.add(freshEp);
                    addedAny = true;
                }
            }
        }
        }
        existing.totalSeasons = fresh.totalSeasons;
        existing.productionStatus = fresh.productionStatus;
        return addedAny;
    }
}
