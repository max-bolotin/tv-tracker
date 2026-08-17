package com.tvtracker.provider;

import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Tries TMDB first; falls back to TVMaze if TMDB is not configured or throws.
 */
@Service
public class MetadataService {

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
        TrackedShow fromTmdb = null;
        TrackedShow fromTvmaze = null;

        if (tmdbId != null && tmdb.isConfigured()) {
            try { fromTmdb = tmdb.fetchDetails(tmdbId); } catch (Exception ignored) {}
        }
        if (tvmazeId != null) {
            try { fromTvmaze = tvmaze.fetchDetails(tvmazeId); } catch (Exception ignored) {}
        }

        // If we have both, merge preferring TMDB for metadata and the provider with more episode data per season
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

            // Merge seasons: for each season number present in either provider, pick the episodes list from the provider that has more episodes
            java.util.Map<Integer, com.tvtracker.model.Season> map = new java.util.TreeMap<>();
            for (var s : fromTvmaze.seasons) map.put(s.number, s);
            for (var s : fromTmdb.seasons) map.putIfAbsent(s.number, s);

            // Now for each season number, if both providers have it, choose the one with more episodes (prefer TMDB on tie)
            java.util.List<com.tvtracker.model.Season> mergedSeasons = new java.util.ArrayList<>();
            for (Integer num : map.keySet()) {
                com.tvtracker.model.Season tSeason = fromTmdb.seasons.stream().filter(s -> s.number == num).findFirst().orElse(null);
                com.tvtracker.model.Season vSeason = fromTvmaze.seasons.stream().filter(s -> s.number == num).findFirst().orElse(null);
                com.tvtracker.model.Season chosen;
                if (tSeason != null && vSeason != null) {
                    int tCount = tSeason.episodes == null ? 0 : tSeason.episodes.size();
                    int vCount = vSeason.episodes == null ? 0 : vSeason.episodes.size();
                    if (tCount >= vCount) chosen = tSeason; else chosen = vSeason;
                } else if (tSeason != null) chosen = tSeason; else chosen = vSeason;
                // If chosen has no episodes but TMDB is available, try to re-fetch from TMDB directly
                if ((chosen == null || chosen.episodes == null || chosen.episodes.isEmpty()) && fromTmdb != null && tmdb.isConfigured()) {
                    try {
                        var seasonRefetch = tmdb.fetchSeason(fromTmdb.tmdbId, num);
                        if (seasonRefetch != null && seasonRefetch.episodes != null && !seasonRefetch.episodes.isEmpty()) {
                            chosen = seasonRefetch;
                        }
                    } catch (Exception ignore) {}
                }
                // defensive copy (ensure non-null)
                if (chosen == null) chosen = new com.tvtracker.model.Season(num);
                com.tvtracker.model.Season copy = new com.tvtracker.model.Season(chosen.number);
                copy.episodes = new java.util.ArrayList<>();
                if (chosen.episodes != null) copy.episodes.addAll(chosen.episodes);
                mergedSeasons.add(copy);
            }
            merged.seasons = mergedSeasons;
            merged.totalSeasons = mergedSeasons.size();
            merged.watchStatus = fromTmdb.watchStatus != null ? fromTmdb.watchStatus : fromTvmaze.watchStatus;
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
