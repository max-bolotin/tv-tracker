package com.tvtracker.provider;

import com.tvtracker.exception.UpstreamServiceException;
import com.tvtracker.model.Season;
import com.tvtracker.model.Actor;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.MovieSearchResult;
import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

  private final Map<String, CachedShow> detailsCache = new ConcurrentHashMap<>();
  private final Map<Long, CachedMovie> movieDetailsCache = new ConcurrentHashMap<>();
  private final Duration showDetailsTtl;
  private final Duration popularTtl;
  private final Duration movieDetailsTtl;
  private final int cacheMaxEntries;
  private final int popularMaxEntries;
  private volatile CachedPopular popularCache;

  private record CachedShow(TrackedShow show, Instant fetchedAt) {
    boolean isExpired(Duration ttl) { return fetchedAt.plus(ttl).isBefore(Instant.now()); }
  }

  private record CachedMovie(MovieMetadataResult result, Instant fetchedAt) {
    boolean isExpired(Duration ttl) { return fetchedAt.plus(ttl).isBefore(Instant.now()); }
  }

  private record CachedPopular(List<ShowSearchResult> shows, Instant fetchedAt) {
    boolean isExpired(Duration ttl) { return fetchedAt.plus(ttl).isBefore(Instant.now()); }
  }

  public MetadataService(TmdbProvider tmdb, TvMazeProvider tvmaze, OmdbProvider omdb) {
    this(tmdb, tvmaze, omdb, Duration.ofHours(12), Duration.ofHours(6),
        Duration.ofHours(12), 1000, 100);
  }

  @Autowired
  public MetadataService(
      TmdbProvider tmdb,
      TvMazeProvider tvmaze,
      OmdbProvider omdb,
      @Value("${app.cache.show-details-ttl:PT12H}") Duration showDetailsTtl,
      @Value("${app.cache.popular-ttl:PT6H}") Duration popularTtl,
      @Value("${app.cache.movie-details-ttl:PT12H}") Duration movieDetailsTtl,
      @Value("${app.cache.max-entries:1000}") int cacheMaxEntries,
      @Value("${app.cache.popular-max-entries:100}") int popularMaxEntries) {
    this.tmdb = tmdb;
    this.tvmaze = tvmaze;
    this.omdb = omdb;
    this.showDetailsTtl = showDetailsTtl;
    this.popularTtl = popularTtl;
    this.movieDetailsTtl = movieDetailsTtl;
    this.cacheMaxEntries = Math.max(1, cacheMaxEntries);
    this.popularMaxEntries = Math.max(1, popularMaxEntries);
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

  public List<MovieSearchResult> searchMovies(String query) {
    if (!tmdb.isConfigured()) {
      throw new UpstreamServiceException("TMDB is not configured");
    }
    return tmdb.searchMovies(query);
  }

  public MovieMetadataResult fetchMovieDetails(long tmdbId) {
    return fetchMovieDetails(tmdbId, true);
  }

  public MovieMetadataResult fetchMovieDetails(long tmdbId, boolean allowOmdb) {
    return fetchMovieDetails(tmdbId, allowOmdb, false);
  }

  public MovieMetadataResult fetchMovieDetailsFresh(long tmdbId, boolean allowOmdb) {
    return fetchMovieDetails(tmdbId, allowOmdb, true);
  }

  private MovieMetadataResult fetchMovieDetails(long tmdbId, boolean allowOmdb, boolean forceRefresh) {
    if (!tmdb.isConfigured()) {
      throw new UpstreamServiceException("TMDB is not configured");
    }
    CachedMovie cached = movieDetailsCache.get(tmdbId);
    if (!forceRefresh && cached != null && !cached.isExpired(movieDetailsTtl)) {
      if (allowOmdb && !cached.result().ratingsRefreshed()
          && hasNoMovieRatings(cached.result().movie())
          && !omdb.isBackoffActive()
          && isMovieOmdbEnrichmentApplicable(cached.result().movie())) {
        return retryMovieRatingsFromCache(tmdbId, cached);
      }
      return new MovieMetadataResult(copyMovie(cached.result().movie()),
          cached.result().ratingsRefreshed(), false);
    }

    try {
      MovieEntry movie = tmdb.fetchMovieDetails(tmdbId);
      MovieMetadataResult result;
      if (!isMovieOmdbEnrichmentApplicable(movie) || !allowOmdb) {
        result = new MovieMetadataResult(movie, true, false);
      } else {
        try {
          omdb.enrichMovie(movie);
          result = new MovieMetadataResult(movie, true, true);
        } catch (UpstreamServiceException e) {
          if (omdb.isBackoffActive()) {
            log.debug("OMDb ratings for movie '{}' deferred while quota backoff is active", movie.title);
          } else {
            log.warn("OMDb enrichment failed for movie '{}'; returning TMDB metadata without ratings: {}",
                movie.title, e.getMessage());
          }
          boolean requestAttempted = e instanceof OmdbProvider.MovieEnrichmentException enrichmentException
              ? enrichmentException.requestAttempted()
              : !omdb.isBackoffActive();
          result = new MovieMetadataResult(movie, false, requestAttempted);
        }
      }
      if (cached != null) {
        MovieEntry merged = copyMovie(cached.result().movie());
        MovieMetadataMerger.merge(merged, movie);
        result = new MovieMetadataResult(merged, result.ratingsRefreshed(),
            result.omdbRequestAttempted());
      }
      movieDetailsCache.put(tmdbId, new CachedMovie(copyResult(result), Instant.now()));
      trimMovieDetailsCache();
      return copyResult(result);
    } catch (RuntimeException e) {
      if (cached != null) {
        log.warn("Movie details refresh failed for TMDB id {}; returning cached metadata: {}",
            tmdbId, e.getMessage());
        return new MovieMetadataResult(copyMovie(cached.result().movie()), false, false);
      }
      throw e;
    }
  }

  private MovieMetadataResult retryMovieRatingsFromCache(long tmdbId, CachedMovie cached) {
    MovieEntry movie = copyMovie(cached.result().movie());
    try {
      omdb.enrichMovie(movie);
      MovieMetadataResult result = new MovieMetadataResult(movie, true, true);
      movieDetailsCache.put(tmdbId, new CachedMovie(copyResult(result), Instant.now()));
      return copyResult(result);
    } catch (UpstreamServiceException e) {
      log.warn("OMDb retry failed for movie '{}'; returning cached TMDB metadata: {}",
          movie.title, e.getMessage());
      return new MovieMetadataResult(copyMovie(cached.result().movie()), false, false);
    }
  }

  public boolean isMovieOmdbEnrichmentApplicable(MovieEntry movie) {
    return movie != null && movie.imdbId != null && !movie.imdbId.isBlank()
        && isMovieReleased(movie.releaseDate) && omdb != null && omdb.isConfigured();
  }

  private static boolean hasNoMovieRatings(MovieEntry movie) {
    return (movie.imdbRating == null || movie.imdbRating.isBlank())
        && (movie.rottenTomatoesScore == null || movie.rottenTomatoesScore.isBlank());
  }

  private boolean isMovieReleased(String releaseDate) {
    if (releaseDate == null || releaseDate.isBlank()) return false;
    try {
      return !LocalDate.parse(releaseDate).isAfter(LocalDate.now());
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  public record MovieMetadataResult(
      MovieEntry movie, boolean ratingsRefreshed, boolean omdbRequestAttempted) {
    public MovieMetadataResult(MovieEntry movie, boolean ratingsRefreshed) {
      this(movie, ratingsRefreshed, ratingsRefreshed);
    }
  }

  public void hydrateMissingTmdbId(TrackedShow show) {
    if (show == null || show.tmdbId != null || show.title == null || show.title.isBlank()) {
      return;
    }
    try {
      List<ShowSearchResult> hits = search(show.title);
      ShowSearchResult match = null;
      // 1. Exact tvmazeId match
      if (show.tvmazeId != null) {
        match = hits.stream()
            .filter(h -> h.tvmazeId != null && h.tvmazeId.equals(show.tvmazeId))
            .findFirst().orElse(null);
      }
      // 2. imdbId match
      if (match == null && show.imdbId != null) {
        match = hits.stream()
            .filter(h -> h.imdbId != null && h.imdbId.equals(show.imdbId))
            .findFirst().orElse(null);
      }
      // 3. Title + year match (most reliable for disambiguation like Perry Mason 1957 vs 2020)
      if (match == null && show.firstAirYear != null) {
        match = hits.stream()
            .filter(h -> h.title != null && h.title.equalsIgnoreCase(show.title)
                && show.firstAirYear.equals(h.firstAirYear))
            .findFirst().orElse(null);
      }
      // 4. Title-only match (last resort)
      if (match == null) {
        match = hits.stream()
            .filter(h -> h.title != null && h.title.equalsIgnoreCase(show.title))
            .findFirst().orElse(null);
      }
      if (match != null && match.tmdbId != null) {
        show.tmdbId = match.tmdbId;
        log.debug("hydrateMissingTmdbId: resolved tmdbId={} for show='{}' (year={}) from title search",
            show.tmdbId, show.title, show.firstAirYear);
      }
    } catch (Exception e) {
      log.warn("hydrateMissingTmdbId failed for show='{}': {}", show.title, e.getMessage());
    }
  }

  public TrackedShow fetchDetails(Long tmdbId, Long tvmazeId) {
    return fetchDetails(tmdbId, tvmazeId, true);
  }

  /**
   * Returns whether OMDb provider has been configured. Exposed for callers (scheduler/tests) to
   * decide whether to run the OMDb enrichment pass without using reflection.
   */
  public boolean isOmdbConfigured() {
    return omdb != null && omdb.isConfigured();
  }

  /**
   * Fetches details and optionally enriches ratings (OMDb).
   * When called as part of large bulk/scheduler runs, pass enrichRatings=false to avoid
   * hitting OMDb rate limits or generating many concurrent requests.
   */
  public TrackedShow fetchDetails(Long tmdbId, Long tvmazeId, boolean enrichRatingsFlag) {
    return fetchDetails(tmdbId, tvmazeId, enrichRatingsFlag, false);
  }

  public TrackedShow fetchDetailsFresh(Long tmdbId, Long tvmazeId) {
    return fetchDetails(tmdbId, tvmazeId, true, true);
  }

  private TrackedShow fetchDetails(
      Long tmdbId, Long tvmazeId, boolean enrichRatingsFlag, boolean forceRefresh) {
    log.debug("fetchDetails: tmdbId={}, tvmazeId={}, tmdbConfigured={}, tvmazeAvailable={}",
        tmdbId, tvmazeId, tmdb.isConfigured(), tvmaze != null);

    // Cache lookup (skip for scheduler bulk runs where enrichRatingsFlag=false)
    String cacheKey = tmdbId != null ? "tmdb:" + tmdbId : (tvmazeId != null ? "tvmaze:" + tvmazeId : null);
    if (enrichRatingsFlag && !forceRefresh && cacheKey != null) {
      CachedShow cached = detailsCache.get(cacheKey);
      if (cached != null && !cached.isExpired(showDetailsTtl)) {
        log.debug("fetchDetails: cache hit for key={}", cacheKey);
        return cached.show().metaCopy(); // return a copy so callers can't mutate the cached entry
      }
    }

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

    if (tvmaze != null && tvmazeId != null) {
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
      if (enrichRatingsFlag) enrichRatings(merged);
      cacheShow(cacheKey, merged, enrichRatingsFlag);
      return merged;
    }

    if (fromTmdb != null) {
      filterZeroSeasonsAndEpisodes(fromTmdb);
      if (enrichRatingsFlag) enrichRatings(fromTmdb);
      cacheShow(cacheKey, fromTmdb, enrichRatingsFlag);
      return fromTmdb;
    }
    if (fromTvmaze != null) {
      filterZeroSeasonsAndEpisodes(fromTvmaze);
      if (enrichRatingsFlag) enrichRatings(fromTvmaze);
      cacheShow(cacheKey, fromTvmaze, enrichRatingsFlag);
      return fromTvmaze;
    }
    if (!forceRefresh && enrichRatingsFlag && cacheKey != null) {
      CachedShow stale = detailsCache.get(cacheKey);
      if (stale != null) {
        log.warn("Show details refresh failed for {}; returning stale cached metadata", cacheKey);
        return stale.show().metaCopy();
      }
    }
    throw new IllegalArgumentException("No valid external ID provided");
  }

  private void cacheShow(String cacheKey, TrackedShow show, boolean shouldCache) {
    if (!shouldCache || cacheKey == null) return;
    show.metadataLastRefreshedAt = Instant.now();
    detailsCache.put(cacheKey, new CachedShow(show.metaCopy(), Instant.now()));
    trimShowDetailsCache();
  }

  private void trimShowDetailsCache() {
    detailsCache.entrySet().removeIf(entry -> entry.getValue().isExpired(showDetailsTtl));
    while (detailsCache.size() > cacheMaxEntries) {
      String oldestKey = detailsCache.entrySet().stream()
          .min(Map.Entry.comparingByValue(
              java.util.Comparator.comparing(CachedShow::fetchedAt)))
          .map(Map.Entry::getKey)
          .orElse(null);
      if (oldestKey == null) return;
      detailsCache.remove(oldestKey);
    }
  }

  private void trimMovieDetailsCache() {
    movieDetailsCache.entrySet().removeIf(entry -> entry.getValue().isExpired(movieDetailsTtl));
    while (movieDetailsCache.size() > cacheMaxEntries) {
      Long oldestKey = movieDetailsCache.entrySet().stream()
          .min(Map.Entry.comparingByValue(
              java.util.Comparator.comparing(CachedMovie::fetchedAt)))
          .map(Map.Entry::getKey)
          .orElse(null);
      if (oldestKey == null) return;
      movieDetailsCache.remove(oldestKey);
    }
  }

  private static MovieMetadataResult copyResult(MovieMetadataResult result) {
    return new MovieMetadataResult(copyMovie(result.movie()), result.ratingsRefreshed(),
        result.omdbRequestAttempted());
  }

  private static MovieEntry copyMovie(MovieEntry source) {
    MovieEntry copy = new MovieEntry();
    copy.id = source.id;
    copy.tmdbId = source.tmdbId;
    copy.mediaType = source.mediaType;
    copy.imdbId = source.imdbId;
    copy.title = source.title;
    copy.overview = source.overview;
    copy.releaseDate = source.releaseDate;
    copy.runtime = source.runtime;
    copy.poster = source.poster;
    copy.cast = new ArrayList<>();
    if (source.cast != null) {
      for (Actor actor : source.cast) {
        copy.cast.add(new Actor(actor.name, actor.profilePath, actor.linkUrl));
      }
    }
    copy.imdbRating = source.imdbRating;
    copy.rottenTomatoesScore = source.rottenTomatoesScore;
    copy.personalRating = source.personalRating;
    copy.status = source.status;
    copy.watchedDate = source.watchedDate;
    copy.lastRefreshedAt = source.lastRefreshedAt;
    return copy;
  }

  /** Removes season 0 and episode 0 entries from a show (in-place). */
  private void filterZeroSeasonsAndEpisodes(TrackedShow show) {
    if (show.seasons == null) return;
    show.seasons.removeIf(s -> s.number == 0);
    for (var s : show.seasons) {
      if (s.episodes != null) s.episodes.removeIf(e -> e.number == 0);
    }
    show.seasons.removeIf(s -> s.episodes == null || s.episodes.isEmpty());
    show.totalSeasons = show.seasons.size();
  }

  /**
   * Resolves imdbId via TMDB external_ids if missing, then enriches show and season ratings via OMDb.
   */
  public void enrichRatings(TrackedShow show) {
    enrichRatings(show, false);
  }

  /**
   * Enrich ratings via OMDb. If strict==true, rethrow unexpected exceptions so the scheduler can
   * report this show's failure and continue with other candidates.
   */
  public OmdbProvider.ShowEnrichmentResult enrichRatings(TrackedShow show, boolean strict) {
    if (!omdb.isConfigured()) {
      return new OmdbProvider.ShowEnrichmentResult(
          OmdbProvider.ShowEnrichmentStatus.SKIPPED, "OMDb is not configured");
    }
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
        return new OmdbProvider.ShowEnrichmentResult(
            OmdbProvider.ShowEnrichmentStatus.SKIPPED, "no IMDb ID");
      }
      OmdbProvider.ShowEnrichmentResult result = omdb.enrichShow(show);
      for (var season : show.seasons) {
        omdb.enrichSeason(show, season);
      }
      if (result != null && result.status() == OmdbProvider.ShowEnrichmentStatus.FAILED
          && !strict) {
        log.warn("enrichRatings failed for show={}: {}", show.title, result.detail());
      }
      return result == null
          ? new OmdbProvider.ShowEnrichmentResult(
              OmdbProvider.ShowEnrichmentStatus.SKIPPED, "no enrichment result")
          : result;
    } catch (Exception e) {
      if (strict) throw e;
      log.warn("enrichRatings failed for show={}: {}", show.title, e.getMessage());
      return new OmdbProvider.ShowEnrichmentResult(
          OmdbProvider.ShowEnrichmentStatus.FAILED, e.getMessage());
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
    if (limit <= 0) return List.of();
    CachedPopular cached = popularCache;
    if (cached != null && !cached.isExpired(popularTtl) && cached.shows().size() >= limit) {
      return copyPopular(cached.shows().subList(0, limit));
    }
    if (tmdb.isConfigured()) {
      try {
        List<ShowSearchResult> fetched = tmdb.fetchPopular(Math.min(limit, popularMaxEntries));
        if (fetched != null && !fetched.isEmpty()) {
          if (cached == null || fetched.size() >= cached.shows().size()
              || cached.isExpired(popularTtl)) {
            popularCache = new CachedPopular(copyPopular(fetched), Instant.now());
          }
          return copyPopular(fetched.subList(0, Math.min(limit, fetched.size())));
        }
      } catch (Exception e) {
        log.warn("Popular shows refresh failed; returning cached list when available: {}", e.getMessage());
      }
    }
    return cached == null ? List.of()
        : copyPopular(cached.shows().subList(0, Math.min(limit, cached.shows().size())));
  }

  private static List<ShowSearchResult> copyPopular(List<ShowSearchResult> shows) {
    List<ShowSearchResult> copies = new ArrayList<>(shows.size());
    for (ShowSearchResult show : shows) {
      ShowSearchResult copy = new ShowSearchResult();
      copy.tmdbId = show.tmdbId;
      copy.tvmazeId = show.tvmazeId;
      copy.title = show.title;
      copy.overview = show.overview;
      copy.posterPath = show.posterPath;
      copy.firstAirYear = show.firstAirYear;
      copy.totalSeasons = show.totalSeasons;
      copy.productionStatus = show.productionStatus;
      copies.add(copy);
    }
    return copies;
  }
}
