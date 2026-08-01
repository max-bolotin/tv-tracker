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
        if (tmdbId != null && tmdb.isConfigured()) {
            try { return tmdb.fetchDetails(tmdbId); } catch (Exception ignored) {}
        }
        if (tvmazeId != null) {
            return tvmaze.fetchDetails(tvmazeId);
        }
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
}
