package com.tvtracker.provider;

import com.tvtracker.model.Season;
import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tries TMDB first; falls back to TVMaze if TMDB is not configured or throws.
 */
@Service
public class MetadataService {

  private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

  private final TmdbProvider tmdb;
  private final TvMazeProvider tvmaze;
  private final OmdbProvider omdb;

  public MetadataService(TmdbProvider tmdb, TvMazeProvider tvmaze, OmdbProvider omdb) {
    this.tmdb = tmdb;
    this.tvmaze = tvmaze;
    this.omdb = omdb;
  }

  public List<ShowSearchResult> search(String query) {
    if (tmdb.isConfigured()) {
      try {
        return tmdb.search(query);
      } catch (Exception ignored) {
      }
    }
    return tvmaze.search(query);
  }

  public void hydrateMissingTmdbId(TrackedShow show) {
    if (show == null || show.tmdbId != null || show.title == null || show.title.isBlank()) {
      return;
    }
    try {
      List<ShowSearchResult> hits = search(show.title);
      ShowSearchResult match = null;
      if (show.tvmazeId != null) {
        match = hits.stream()
            .filter(h -> h.tvmazeId != null && h.tvmazeId.equals(show.tvmazeId))
            .findFirst()
            .orElse(null);
      }
      if (match == null && show.imdbId != null) {
        match = hits.stream()
            .filter(h -> h.imdbId != null && h.imdbId.equals(show.imdbId))
            .findFirst()
            .orElse(null);
      }
      if (match == null) {
        match = hits.stream()
            .filter(h -> h.title != null && h.title.equalsIgnoreCase(show.title))
            .findFirst()
            .orElse(null);
      }
      if (match != null && match.tmdbId != null) {
        show.tmdbId = match.tmdbId;
        log.debug("hydrateMissingTmdbId: resolved tmdbId={} for show='{}' from title search", show.tmdbId, show.title);
      }
    } catch (Exception e) {
      log.warn("hydrateMissingTmdbId failed for show='{}': {}", show.title, e.getMessage());
    }
  }

  public TrackedShow fetchDetails(Long tmdbId, Long tvmazeId) {
    log.debug("fetchDetails: tmdbId={}, tvmazeId={}, tmdbConfigured={}, tvmazeAvailable={}",
        tmdbId, tvmazeId, tmdb.isConfigured(), tvmaze != null);

    CompletableFuture<TrackedShow> fTmdb;
    CompletableFuture<TrackedShow> fTvmaze;

    if (tmdbId != null && tmdb.isConfigured()) {
      fTmdb = CompletableFuture.supplyAsync(() -> {
        try {
          return tmdb.fetchDetails(tmdbId);
        } catch (Exception e) {
          log.warn("tmdb.fetchDetails failed for id {}: {}", tmdbId, e.getMessage());
          return null;
        }
      });
    } else {
      log.debug("fetchDetails: skipping TMDB call for tmdbId={} because it is null or not configured", tmdbId);
      fTmdb = CompletableFuture.completedFuture(null);
    }

    if (tvmazeId != null) {
      fTvmaze = CompletableFuture.supplyAsync(() -> {
        try {
          return tvmaze.fetchDetails(tvmazeId);
        } catch (Exception e) {
          log.warn("tvmaze.fetchDetails failed for id {}: {}", tvmazeId, e.getMessage());
          return null;
        }
      });
    } else {
      fTvmaze = CompletableFuture.completedFuture(null);
    }

    TrackedShow fromTmdb = null;
    TrackedShow fromTvmaze = null;
    try {
      CompletableFuture.allOf(fTmdb, fTvmaze).join();
      fromTmdb = fTmdb.getNow(null);
      fromTvmaze = fTvmaze.getNow(null);
    } catch (Exception e) {
      log.warn("fetchDetails: parallel fetch join failed: {}", e.getMessage());
    }

    log.debug("fetchDetails: tmdbId={}, tvmazeId={}, fromTmdb={}, fromTvmaze={}", tmdbId, tvmazeId,
        fromTmdb != null, fromTvmaze != null);

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
      merged.productionStatus = fromTmdb.productionStatus != null ? fromTmdb.productionStatus
          : fromTvmaze.productionStatus;
      merged.cast = fromTmdb.cast != null && !fromTmdb.cast.isEmpty() ? fromTmdb.cast : fromTvmaze.cast;
      log.debug("fetchDetails: merged show='{}' castSize={} fromTmdb={} fromTvmaze={}",
          merged.title,
          merged.cast == null ? 0 : merged.cast.size(),
          fromTmdb.cast != null ? fromTmdb.cast.size() : 0,
          fromTvmaze.cast != null ? fromTvmaze.cast.size() : 0);

      // Build union of season numbers from both providers
      Set<Integer> seasonNums = new TreeSet<>();
        if (fromTmdb.seasons != null) {
            fromTmdb.seasons.forEach(s -> seasonNums.add(s.number));
        }
        if (fromTvmaze.seasons != null) {
            fromTvmaze.seasons.forEach(s -> seasonNums.add(s.number));
        }

      List<Season> mergedSeasons = new ArrayList<>();
      for (Integer num : seasonNums) {
        com.tvtracker.model.Season tSeason = (fromTmdb.seasons == null) ? null
            : fromTmdb.seasons.stream().filter(s -> s.number == num).findFirst().orElse(null);
        com.tvtracker.model.Season vSeason = (fromTvmaze.seasons == null) ? null
            : fromTvmaze.seasons.stream().filter(s -> s.number == num).findFirst().orElse(null);

        int tCount = tSeason == null || tSeason.episodes == null ? 0 : tSeason.episodes.size();
        int vCount = vSeason == null || vSeason.episodes == null ? 0 : vSeason.episodes.size();
        String chosenBy = "none";

        com.tvtracker.model.Season chosen = null;
        if (tSeason != null && vSeason != null) {
          if (tCount >= vCount) {
            chosen = tSeason;
            chosenBy = "TMDB";
          } else {
            chosen = vSeason;
            chosenBy = "TVMAZE";
          }
        } else if (tSeason != null) {
          chosen = tSeason;
          chosenBy = "TMDB";
        } else if (vSeason != null) {
          chosen = vSeason;
          chosenBy = "TVMAZE";
        }

        // If chosen has no episodes but TMDB is configured, and we have a valid tmdbId, try to re-fetch from TMDB directly
        if ((chosen == null || chosen.episodes == null || chosen.episodes.isEmpty())
            && tmdb.isConfigured() && fromTmdb.tmdbId != null) {
          try {
            var seasonRefetch = tmdb.fetchSeason(fromTmdb.tmdbId, num);
            if (seasonRefetch != null && seasonRefetch.episodes != null
                && !seasonRefetch.episodes.isEmpty()) {
              chosen = seasonRefetch;
              chosenBy = "TMDB-refetch";
            }
          } catch (Exception ignore) { /* already logged fetchSeason inside tmdb provider */ }
        }

        log.debug("fetchDetails: show='{}' season={} tCount={} vCount={} chosen={}", merged.title,
            num, tCount, vCount, chosenBy);

          if (chosen == null) {
              chosen = new com.tvtracker.model.Season(num);
          }
        // Skip season number 0 (special/behind-the-scenes) intentionally
        if (chosen.number == 0) {
          log.debug("fetchDetails: skipping season number 0 for show='{}'", merged.title);
          continue;
        }
        com.tvtracker.model.Season copy = new com.tvtracker.model.Season(chosen.number);
        copy.episodes = new ArrayList<>();
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
        if (!copy.episodes.isEmpty()) {
          mergedSeasons.add(copy);
        } else {
          log.debug("fetchDetails: skipping empty season {} for show='{}'", num, merged.title);
        }
      }
      merged.seasons = mergedSeasons;
      merged.totalSeasons = mergedSeasons.size();
      merged.watchStatus =
          fromTmdb.watchStatus != null ? fromTmdb.watchStatus : fromTvmaze.watchStatus;
      log.debug("fetchDetails: merged show='{}' totalSeasons={} watchStatusFromTmdb={}",
          merged.title, merged.totalSeasons, fromTmdb.watchStatus != null);
      enrichRatings(merged);
      return merged;
    }

    if (fromTmdb != null) { enrichRatings(fromTmdb); return fromTmdb; }
    if (fromTvmaze != null) { enrichRatings(fromTvmaze); return fromTvmaze; }
    throw new IllegalArgumentException("No valid external ID provided");
  }

  /**
   * Resolves imdbId via TMDB external_ids if missing, then enriches show and season ratings via OMDb.
   */
  public void enrichRatings(TrackedShow show) {
    if (!omdb.isConfigured()) return;
    try {
      if (show.imdbId == null && show.tmdbId != null && tmdb.isConfigured()) {
        show.imdbId = tmdb.fetchImdbId(show.tmdbId);
        log.debug("enrichRatings: resolved imdbId={} for show='{}' via TMDB (tmdbId={})", show.imdbId, show.title, show.tmdbId);
      }
      if (show.imdbId == null && show.tvmazeId != null) {
        show.imdbId = tvmaze.fetchImdbId(show.tvmazeId);
        log.debug("enrichRatings: resolved imdbId={} for show='{}' via TVMaze (tvmazeId={})", show.imdbId, show.title, show.tvmazeId);
      }
      if (show.imdbId == null) {
        log.warn("enrichRatings: no imdbId for show='{}' (tmdbId={}, tvmazeId={}) — skipping ratings",
            show.title, show.tmdbId, show.tvmazeId);
        return;
      }
      omdb.enrichShow(show);
      for (var season : show.seasons) {
        omdb.enrichSeason(show, season);
      }
    } catch (Exception e) {
      log.warn("enrichRatings failed for show={}: {}", show.title, e.getMessage());
    }
  }

  public List<Long> fetchRecentlyUpdatedTmdbIds() {
    if (tmdb.isConfigured()) {
      try {
        return tmdb.fetchRecentlyUpdatedIds();
      } catch (Exception ignored) {
      }
    }
    return List.of();
  }

  public List<Long> fetchRecentlyUpdatedTvmazeIds() {
    try {
      return tvmaze.fetchRecentlyUpdatedIds();
    } catch (Exception ignored) {
    }
    return List.of();
  }

  public List<ShowSearchResult> fetchPopular(int limit) {
    if (tmdb.isConfigured()) {
      try {
        return tmdb.fetchPopular(limit);
      } catch (Exception ignored) {
      }
    }
    return List.of();
  }
}
