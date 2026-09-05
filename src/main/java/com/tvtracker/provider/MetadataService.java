package com.tvtracker.provider;

import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Tries TMDB first; falls back to TVMaze if TMDB is not configured or throws.
 */
@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    private final TmdbProvider tmdb;
    private final TvMazeProvider tvmaze;

    public MetadataService(TmdbProvider tmdb, TvMazeProvider tvmaze) {
        this.tmdb = tmdb;
        this.tvmaze = tvmaze;
    }

    public List<ShowSearchResult> search(String query) {
        if (tmdb.isConfigured()) {
            try { return tmdb.search(query); } catch (Exception ignored) {}
        }
        return tvmaze.search(query);
    }

    public TrackedShow fetchDetails(Long tmdbId, Long tvmazeId) {
        java.util.concurrent.CompletableFuture<TrackedShow> fTmdb;
        java.util.concurrent.CompletableFuture<TrackedShow> fTvmaze;

        if (tmdbId != null && tmdb.isConfigured()) {
            fTmdb = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return tmdb.fetchDetails(tmdbId); } catch (Exception e) { log.warn("tmdb.fetchDetails failed for id {}: {}", tmdbId, e.getMessage()); return null; }
            });
        } else {
            fTmdb = java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        if (tvmazeId != null) {
            fTvmaze = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return tvmaze.fetchDetails(tvmazeId); } catch (Exception e) { log.warn("tvmaze.fetchDetails failed for id {}: {}", tvmazeId, e.getMessage()); return null; }
            });
        } else {
            fTvmaze = java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        TrackedShow fromTmdb = null;
        TrackedShow fromTvmaze = null;
        try {
            java.util.concurrent.CompletableFuture.allOf(fTmdb, fTvmaze).join();
            fromTmdb = fTmdb.getNow(null);
            fromTvmaze = fTvmaze.getNow(null);
        } catch (Exception e) {
            log.warn("fetchDetails: parallel fetch join failed: {}", e.getMessage());
        }

        log.info("fetchDetails: tmdbId={}, tvmazeId={}, fromTmdb={}, fromTvmaze={}", tmdbId, tvmazeId, fromTmdb != null, fromTvmaze != null);

        // If we have both, merge preferring TMDB for basic metadata and choosing the provider with more episode data per season
        if (fromTmdb != null && fromTvmaze != null) {
            TrackedShow merged = new TrackedShow();
            // prefer TMDB for basic metadata if available
            merged.id = fromTmdb.id != null ? fromTmdb.id : fromTvmaze.id;
            merged.tmdbId = fromTmdb.tmdbId != null ? fromTmdb.tmdbId : fromTvmaze.tmdbId;
            merged.tvmazeId = fromTmdb.tvmazeId != null ? fromTmdb.tvmazeId : fromTvmaze.tvmazeId;
            merged.title = fromTmdb.title != null ? fromTmdb.title : fromTvmaze.title;
            merged.overview = fromTmdb.overview != null ? fromTmdb.overview : fromTvmaze.overview;
            merged.posterPath = fromTmdb.posterPath != null ? fromTmdb.posterPath : fromTvmaze.posterPath;
            merged.productionStatus = fromTmdb.productionStatus != null ? fromTmdb.productionStatus : fromTvmaze.productionStatus;

            // Build union of season numbers from both providers
            java.util.Set<Integer> seasonNums = new java.util.TreeSet<>();
            if (fromTmdb.seasons != null) fromTmdb.seasons.forEach(s -> seasonNums.add(s.number));
            if (fromTvmaze.seasons != null) fromTvmaze.seasons.forEach(s -> seasonNums.add(s.number));

            java.util.List<com.tvtracker.model.Season> mergedSeasons = new java.util.ArrayList<>();
            for (Integer num : seasonNums) {
                com.tvtracker.model.Season tSeason = fromTmdb.seasons.stream().filter(s -> s.number == num).findFirst().orElse(null);
                com.tvtracker.model.Season vSeason = fromTvmaze.seasons.stream().filter(s -> s.number == num).findFirst().orElse(null);

                int tCount = tSeason == null || tSeason.episodes == null ? 0 : tSeason.episodes.size();
                int vCount = vSeason == null || vSeason.episodes == null ? 0 : vSeason.episodes.size();
                String chosenBy = "none";

                com.tvtracker.model.Season chosen = null;
                if (tSeason != null && vSeason != null) {
                    if (tCount >= vCount) { chosen = tSeason; chosenBy = "TMDB"; } else { chosen = vSeason; chosenBy = "TVMAZE"; }
                } else if (tSeason != null) { chosen = tSeason; chosenBy = "TMDB"; } else if (vSeason != null) { chosen = vSeason; chosenBy = "TVMAZE"; }

                // If chosen has no episodes but TMDB is available, try to re-fetch from TMDB directly
                if ((chosen == null || chosen.episodes == null || chosen.episodes.isEmpty()) && fromTmdb != null && tmdb.isConfigured()) {
                    try {
                        var seasonRefetch = tmdb.fetchSeason(fromTmdb.tmdbId, num);
                        if (seasonRefetch != null && seasonRefetch.episodes != null && !seasonRefetch.episodes.isEmpty()) {
                            chosen = seasonRefetch;
                            chosenBy = "TMDB-refetch";
                        }
                    } catch (Exception ignore) { /* already logged fetchSeason inside tmdb provider */ }
                }

                log.info("fetchDetails: show='{}' season={} tCount={} vCount={} chosen={}", merged.title, num, tCount, vCount, chosenBy);

                if (chosen == null) chosen = new com.tvtracker.model.Season(num);
                // Skip season number 0 (special/behind-the-scenes) intentionally
                if (chosen.number == 0) {
                    log.info("fetchDetails: skipping season number 0 for show='{}'", merged.title);
                    continue;
                }
                com.tvtracker.model.Season copy = new com.tvtracker.model.Season(chosen.number);
                copy.episodes = new java.util.ArrayList<>();
                if (chosen.episodes != null) {
                    for (var ep : chosen.episodes) {
                        if (ep.number == 0) {
                            // skip episodes numbered 0
                            continue;
                        }
                        copy.episodes.add(ep);
                    }
                }
                // Only include seasons with episodes
                if (copy.episodes != null && !copy.episodes.isEmpty()) {
                    mergedSeasons.add(copy);
                } else {
                    log.info("fetchDetails: skipping empty season {} for show='{}'", num, merged.title);
                }
            }
            merged.seasons = mergedSeasons;
            merged.totalSeasons = mergedSeasons.size();
            merged.watchStatus = fromTmdb.watchStatus != null ? fromTmdb.watchStatus : fromTvmaze.watchStatus;
            log.info("fetchDetails: merged show='{}' totalSeasons={} watchStatusFromTmdb={}", merged.title, merged.totalSeasons, fromTmdb.watchStatus != null);
            return merged;
        }

        if (fromTmdb != null) return fromTmdb;
        if (fromTvmaze != null) return fromTvmaze;
        throw new IllegalArgumentException("No valid external ID provided");
    }

    public List<Long> fetchRecentlyUpdatedTmdbIds() {
        if (tmdb.isConfigured()) {
            try { return tmdb.fetchRecentlyUpdatedIds(); } catch (Exception ignored) {}
        }
        return List.of();
    }

    public List<Long> fetchRecentlyUpdatedTvmazeIds() {
        try { return tvmaze.fetchRecentlyUpdatedIds(); } catch (Exception ignored) {}
        return List.of();
    }

    public List<ShowSearchResult> fetchPopular(int limit) {
        if (tmdb.isConfigured()) {
            try { return tmdb.fetchPopular(limit); } catch (Exception ignored) {}
        }
        return List.of();
    }
}
