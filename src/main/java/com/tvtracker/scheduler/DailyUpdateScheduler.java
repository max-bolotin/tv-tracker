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
        log.info("Running daily episode update check...");
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
            List<TrackedShow> upToDate = shows.stream()
                    .filter(s -> s.watchStatus == WatchStatus.UP_TO_DATE)
                    .toList();

            if (upToDate.isEmpty()) return;

            Set<Long> updatedTmdb = Set.copyOf(metadata.fetchRecentlyUpdatedTmdbIds());
            Set<Long> updatedTvmaze = Set.copyOf(metadata.fetchRecentlyUpdatedTvmazeIds());

            boolean anyChanged = false;
            for (TrackedShow show : upToDate) {
                boolean hasUpdate = (show.tmdbId != null && updatedTmdb.contains(show.tmdbId))
                        || (show.tvmazeId != null && updatedTvmaze.contains(show.tvmazeId));

                if (hasUpdate) {
                    try {
                        TrackedShow fresh = metadata.fetchDetails(show.tmdbId, show.tvmazeId);
                        boolean hasNewEpisodes = mergeNewEpisodes(show, fresh);
                        if (hasNewEpisodes) {
                            show.watchStatus = WatchStatus.WATCHING_NOW;
                            log.info("Show '{}' moved to WATCHING_NOW (new episodes added)", show.title);
                        } else {
                            log.info("Show '{}' was updated but has no new episodes — keeping UP_TO_DATE", show.title);
                        }
                        storage.save(userId, show);
                        anyChanged = true;
                    } catch (Exception e) {
                        log.warn("Failed to refresh show '{}': {}", show.title, e.getMessage());
                    }
                }
            }
            if (!anyChanged) log.info("No UP_TO_DATE shows have new episodes.");

            // Heal any WATCHING_NOW shows where all episodes are already watched
            // (e.g. stuck there from a previous bug or an empty-season false-positive)
            for (TrackedShow show : shows) {
                if (show.watchStatus != WatchStatus.WATCHING_NOW) continue;
                WatchStatus before = show.watchStatus;
                show.recalculateStatus();
                if (show.watchStatus != before) {
                    storage.save(userId, show);
                    log.info("Show '{}' healed: {} → {}", show.title, before, show.watchStatus);
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
            var existingSeason = existing.seasons.stream()
                    .filter(s -> s.number == freshSeason.number).findFirst();
            if (existingSeason.isEmpty()) {
                // always add the season entry (even if empty) so we remember announced seasons
                existing.seasons.add(freshSeason);
                if (freshSeason.episodes != null && !freshSeason.episodes.isEmpty()) addedAny = true;
            } else {
                var es = existingSeason.get();
                for (var freshEp : freshSeason.episodes) {
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
