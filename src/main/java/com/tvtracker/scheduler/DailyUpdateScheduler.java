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
import java.util.stream.Collectors;

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
        try {
            List<TrackedShow> shows = storage.loadAll();
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
                    // Re-fetch metadata to get new seasons/episodes
                    try {
                        TrackedShow fresh = metadata.fetchDetails(show.tmdbId, show.tvmazeId);
                        // Merge: preserve watched state, add new episodes as unwatched
                        mergeNewEpisodes(show, fresh);
                        show.watchStatus = WatchStatus.WATCHING_NOW;
                        storage.save(show);
                        anyChanged = true;
                        log.info("Show '{}' moved to WATCHING_NOW (new episodes detected)", show.title);
                    } catch (Exception e) {
                        log.warn("Failed to refresh show '{}': {}", show.title, e.getMessage());
                    }
                }
            }
            if (!anyChanged) log.info("No UP_TO_DATE shows have new episodes.");
        } catch (Exception e) {
            log.error("Daily update check failed", e);
        }
    }

    private void mergeNewEpisodes(TrackedShow existing, TrackedShow fresh) {
        for (var freshSeason : fresh.seasons) {
            var existingSeason = existing.seasons.stream()
                    .filter(s -> s.number == freshSeason.number).findFirst();
            if (existingSeason.isEmpty()) {
                existing.seasons.add(freshSeason); // brand new season
            } else {
                var es = existingSeason.get();
                for (var freshEp : freshSeason.episodes) {
                    boolean alreadyExists = es.episodes.stream().anyMatch(e -> e.number == freshEp.number);
                    if (!alreadyExists) es.episodes.add(freshEp);
                }
            }
        }
        existing.totalSeasons = fresh.totalSeasons;
        existing.productionStatus = fresh.productionStatus;
    }
}
