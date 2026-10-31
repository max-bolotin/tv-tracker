package com.tvtracker.scheduler;

import com.tvtracker.model.Season;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.model.WatchStatus;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.provider.OmdbProvider;
import com.tvtracker.storage.JsonStorageService;
import jakarta.annotation.Nullable;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyUpdateScheduler {

  private static final Logger log = LoggerFactory.getLogger(DailyUpdateScheduler.class);

  private final JsonStorageService storage;
  private final MetadataService metadata;

  // OMDb throttling and staleness configuration
  private final int omdbStalenessMinDays;
  private final int omdbStalenessMaxDays;
  private final long omdbRequestPauseMs;

  public DailyUpdateScheduler(JsonStorageService storage, MetadataService metadata,
      @Value("${omdb.rating.staleness-min-days:7}") int omdbStalenessMinDays,
      @Value("${omdb.rating.staleness-max-days:21}") int omdbStalenessMaxDays,
      @Value("${omdb.rating.request-pause-ms:1000}") long omdbRequestPauseMs) {
    this.storage = storage;
    this.metadata = metadata;
    this.omdbStalenessMinDays = omdbStalenessMinDays;
    this.omdbStalenessMaxDays = omdbStalenessMaxDays;
    this.omdbRequestPauseMs = omdbRequestPauseMs;
  }

  @Scheduled(cron = "${app.scheduler.cron}")
  public void checkForNewEpisodes() {
    log.info("Scheduled daily update started");
    try {
      doCheck();
      log.info("Scheduled daily update finished successfully");
    } catch (Exception e) {
      log.error("Scheduled daily update failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Called by the manual refresh endpoint — same logic, no cron restriction.
   */
  public void doCheck() {
    // default behavior (used by scheduler/manual trigger) - run for all user files
    try {
      List<String> users = storage.listUserIds();
      log.info("Scheduled daily update: running checks for {} user(s)", users.size());
      // Build map of canonical show key -> list of references (userId + TrackedShow)
      Map<String, List<Entry<String, TrackedShow>>> showMap = new LinkedHashMap<>();
      Map<String, TrackedShow> refreshedShowDetails = new LinkedHashMap<>();
      for (String userId : users) {
        try {
          var userShows = storage.loadAll(userId);
          if (userShows == null || userShows.isEmpty()) {
            log.debug("Scheduled daily update: skipping user {} - no tracked shows", userId);
            continue;
          }
          for (TrackedShow s : userShows) {
            // Ensure we have TMDB id when possible to allow canonicalization and single fetch
            try {
              if (s.tmdbId == null) {
                metadata.hydrateMissingTmdbId(s);
              }
            } catch (Exception e) {
              log.debug(
                  "Scheduled daily update: hydrateMissingTmdbId failed for show '{}' user {}: {}",
                  s.title, userId, e.getMessage());
            }

            // Determine canonical key: prefer TMDB id, then TVMAZE id, then title
            String key;
            if (s.tmdbId != null) {
              key = "tmdb:" + s.tmdbId;
            } else if (s.tvmazeId != null) {
              key = "tvmaze:" + s.tvmazeId;
            } else if (s.title != null) {
              key = "title:" + s.title.trim().toLowerCase();
            } else {
              key = "unknown:" + UUID.randomUUID();
            }

            showMap.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new AbstractMap.SimpleEntry<>(userId, s));
          }
        } catch (Exception e) {
          log.error("Scheduled daily update: failed to load shows for user {}: {}", userId,
              e.getMessage(), e);
        }
      }

      log.info(
          "Scheduled daily update: built map - {} show-references across {} users, deduped to {} unique shows",
          showMap.values().stream().mapToInt(List::size).sum(), users.size(), showMap.size());

      // For each unique show, fetch once and apply updates to all user references
      Set<String> modifiedUsers = new HashSet<>();
      for (var entry : showMap.entrySet()) {
        String key = entry.getKey();
        List<Map.Entry<String, TrackedShow>> refs = entry.getValue();
        if (refs.isEmpty()) {
          continue;
        }

        // pick representative to decide which ids to use for fetch
        TrackedShow rep = refs.getFirst().getValue();
        Long tmdbId = rep.tmdbId;
        Long tvmazeId = rep.tvmazeId;

        try {
          // Avoid bulk OMDb enrichment from the scheduler to prevent provider auth/rate issues
          TrackedShow fresh = metadata.fetchDetails(tmdbId, tvmazeId, false);
          refreshedShowDetails.put(key, fresh);
          // Apply fresh data to each user copy
          for (var ref : refs) {
            String userId = ref.getKey();
            TrackedShow existing = ref.getValue();
            try {
              // hydrate TMDB id if missing
              if (existing.tmdbId == null && fresh.tmdbId != null) {
                existing.tmdbId = fresh.tmdbId;
              }

              // cast handling: prefer fresh when available, but keep existing non-empty cast when fresh is empty
              if (fresh.cast != null && (existing.cast == null || existing.cast.isEmpty()
                  || !fresh.cast.isEmpty())) {
                existing.cast = fresh.cast;
              }

              // Merge episodes: reuse mergeNewEpisodes semantics by adding missing episodes into existing
              boolean addedAny = mergeNewEpisodes(existing, fresh);

              // If the show was UP_TO_DATE and we detected new seasons/episodes, move to WATCHING_NOW
              if (existing.watchStatus == WatchStatus.UP_TO_DATE && addedAny) {
                existing.watchStatus = WatchStatus.WATCHING_NOW;
                log.debug(
                    "Scheduled daily update: user {} - show '{}' moved to WATCHING_NOW (new episodes)",
                    userId, existing.title);
              }

              // Ensure personalRating preserved (mergeNewEpisodes preserves other fields)
              // Do NOT enrich ratings here — a single deduped OMDb pass will run later

              // mark this user's data as modified so we persist later
              modifiedUsers.add(userId);
            } catch (Exception e) {
              log.warn(
                  "Scheduled daily update: failed applying fresh data for show '{}' to user {}: {}",
                  existing.title, userId, e.getMessage());
            }
          }
        } catch (Exception e) {
          log.warn("Scheduled daily update: failed to fetch metadata for key {}: {}", key,
              e.getMessage());
        }
      }

      // Build per-user show lists from the in-memory showMap (which has all updates applied)
      Map<String, List<TrackedShow>> userShowsMap = new LinkedHashMap<>();
      for (var refs : showMap.values()) {
        for (var ref : refs) {
          userShowsMap.computeIfAbsent(ref.getKey(), k -> new ArrayList<>()).add(ref.getValue());
        }
      }

      // Persist modified user files
      for (String userId : modifiedUsers) {
        try {
          var shows = userShowsMap.getOrDefault(userId, List.of());
          storage.saveAll(userId, shows);
          log.info("Scheduled daily update: persisted updated file for user {} ({} shows)", userId,
              shows.size());
        } catch (Exception e) {
          log.error("Scheduled daily update: failed to persist updated shows for user {}: {}",
              userId, e.getMessage(), e);
        }
      }

      // --- OMDb rating enrichment pass (deduped, throttled, randomized staleness) ---
      try {
        // Check OMDb configured via MetadataService helper
        if (!metadata.isOmdbConfigured()) {
          log.debug("OMDb not configured — skipping rating enrichment pass");
        } else {
          List<String> unratedCandidates = new ArrayList<>();
          List<String> ratedCandidates = new ArrayList<>();
          for (var entry : showMap.entrySet()) {
            var refs = entry.getValue();
            if (refs == null || refs.isEmpty()) {
              continue;
            }
            TrackedShow rep = refs.getFirst().getValue();
            if (rep == null) {
              continue;
            }
            LocalDate firstAir = getFirstEpisodeAir(refs, refreshedShowDetails.get(entry.getKey()));
            if (firstAir != null && firstAir.isAfter(LocalDate.now())) {
              log.info("OMDb enrichment skipped show '{}': first episode airs in the future ({})",
                  rep.title, firstAir);
              continue;
            }

            boolean unrated = refs.stream()
                .map(Entry::getValue)
                .filter(java.util.Objects::nonNull)
                .anyMatch(show -> !hasRatingValue(show.imdbRating));
            if (unrated) {
              unratedCandidates.add(entry.getKey());
              continue;
            }

            // Determine last fetched timestamp (support both new and legacy fields)
            String lastFetched = rep.ratingLastFetched != null
                ? rep.ratingLastFetched
                : rep.ratingsUpdatedAt;

            // Skip dormant shows only when a rating value is already stored.
            LocalDate latestAir = getLatestAir(rep);
            if (latestAir != null && latestAir.isBefore(
                LocalDate.now().minusMonths(6))) {
              continue;
            }

            // Randomized staleness window
            boolean include = false;
            if (lastFetched == null) {
              include = true;
            } else {
              try {
                LocalDate f = LocalDate.parse(lastFetched);
                int threshold = ThreadLocalRandom.current()
                    .nextInt(omdbStalenessMinDays, omdbStalenessMaxDays + 1);
                if (f.isBefore(LocalDate.now().minusDays(threshold))) {
                  include = true;
                }
              } catch (Exception ignore) {
                include = true;
              }
            }
            if (include) {
              ratedCandidates.add(entry.getKey());
            }
          }

          List<String> omdbCandidates = new ArrayList<>(unratedCandidates);
          omdbCandidates.addAll(ratedCandidates);
          log.info("OMDb enrichment: {} candidate show(s) selected", omdbCandidates.size());
          log.info("OMDb enrichment candidates: {}", omdbCandidates.stream()
              .map(key -> showMap.get(key).getFirst().getValue().title)
              .toList());
          for (String key : omdbCandidates) {
            var refs = showMap.get(key);
            if (refs == null || refs.isEmpty()) {
              continue;
            }
            TrackedShow rep = refs.getFirst().getValue();
            if (rep == null) {
              continue;
            }
            try {
              OmdbProvider.ShowEnrichmentResult result = metadata.enrichRatings(rep, true);
              if (result == null) {
                log.info("OMDb enrichment skipped show '{}': no result was returned", rep.title);
                continue;
              }
              switch (result.status()) {
                case UPDATED -> log.info("OMDb enrichment updated show '{}'", rep.title);
                case NO_RATING, NO_RESULT, SKIPPED ->
                    log.info("OMDb enrichment skipped show '{}': {}", rep.title, result.detail());
                case FAILED ->
                    log.warn("OMDb provider returned a failure for '{}': {}", rep.title,
                        result.detail());
              }
              if (result.status() == OmdbProvider.ShowEnrichmentStatus.FAILED
                  && ("OMDb authentication failed".equals(result.detail())
                      || (result.detail() != null && result.detail().contains("HTTP 429")))) {
                log.warn("Stopping remaining rating requests after an OMDb authentication/quota rejection");
                break;
              }
              if (result.status() != OmdbProvider.ShowEnrichmentStatus.UPDATED
                  || rep.ratingsUpdatedAt == null) {
                continue;
              }

              // propagate ratings to all other user copies of the same show
              for (var ref : refs) {
                TrackedShow copy = ref.getValue();
                if (copy != rep) {
                  copy.imdbRating = rep.imdbRating;
                  copy.rtRating = rep.rtRating;
                  copy.ratingLastFetched = rep.ratingLastFetched;
                  copy.ratingsUpdatedAt = rep.ratingsUpdatedAt;
                }
                modifiedUsers.add(ref.getKey());
              }

            } catch (Exception e) {
              String msg = e.getMessage() == null ? e.toString() : e.getMessage();
              log.warn("OMDb rating enrichment threw for '{}': {}", rep.title, msg);
              if (msg.contains("HTTP 401") || msg.contains("HTTP 429")) {
                log.warn("Aborting the remaining OMDb candidate loop due to authentication/quota response");
                break;
              }
            }

            // pause between OMDb requests
            try {
              Thread.sleep(omdbRequestPauseMs);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
          }

          // Persist any user files modified by OMDb pass
          for (String userId : new HashSet<>(modifiedUsers)) {
            try {
              var shows = userShowsMap.getOrDefault(userId, List.of());
              storage.saveAll(userId, shows);
              log.info("OMDb enrichment: persisted updated file for user {} ({} shows)", userId,
                  shows.size());
            } catch (Exception e) {
              log.error("OMDb enrichment: failed to persist updated shows for user {}: {}", userId,
                  e.getMessage(), e);
            }
          }
        }
      } catch (Exception e) {
        log.error("OMDb enrichment pass failed: {}", e.getMessage(), e);
      }

      log.info("Scheduled daily update: completed all user checks");
    } catch (Exception e) {
      log.error("Scheduled daily update: failed to enumerate user files: {}", e.getMessage(), e);
    }
  }

  @Nullable
  private static LocalDate getLatestAir(TrackedShow rep) {
    LocalDate latestAir = null;
    if (rep.seasons != null) {
      for (var s : rep.seasons) {
        if (s.episodes == null) {
          continue;
        }
        for (var e : s.episodes) {
          if (e.airDate == null) {
            continue;
          }
          try {
            LocalDate d = LocalDate.parse(e.airDate);
            if (latestAir == null || d.isAfter(latestAir)) {
              latestAir = d;
            }
          } catch (Exception ignore) {
          }
        }
      }
    }
    return latestAir;
  }

  @Nullable
  private static LocalDate getFirstEpisodeAir(
      List<Entry<String, TrackedShow>> refs, @Nullable TrackedShow refreshed) {
    List<TrackedShow> sources = new ArrayList<>();
    for (Entry<String, TrackedShow> ref : refs) {
      if (ref.getValue() != null) sources.add(ref.getValue());
    }
    if (refreshed != null) sources.add(refreshed);

    int firstSeasonNumber = Integer.MAX_VALUE;
    int firstEpisodeNumber = Integer.MAX_VALUE;
    for (TrackedShow show : sources) {
      if (show == null || show.seasons == null) continue;
      for (Season season : show.seasons) {
        if (season.number <= 0 || season.episodes == null) continue;
        for (var episode : season.episodes) {
          if (episode.number <= 0) continue;
          if (season.number < firstSeasonNumber
              || (season.number == firstSeasonNumber && episode.number < firstEpisodeNumber)) {
            firstSeasonNumber = season.number;
            firstEpisodeNumber = episode.number;
          }
        }
      }
    }
    if (firstSeasonNumber == Integer.MAX_VALUE) return null;
    for (TrackedShow show : sources) {
      if (show == null || show.seasons == null) continue;
      for (Season season : show.seasons) {
        if (season.number != firstSeasonNumber || season.episodes == null) continue;
        for (var episode : season.episodes) {
          if (episode.number == firstEpisodeNumber) {
            LocalDate airDate = parseAirDate(episode.airDate);
            if (airDate != null) return airDate;
          }
        }
      }
    }
    return null;
  }

  @Nullable
  private static LocalDate parseAirDate(String airDate) {
    if (airDate == null || airDate.isBlank()) return null;
    try {
      return LocalDate.parse(airDate);
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * Refresh check for a specific user.
   */
  public boolean doCheck(String userId) {
    RefreshResult result = doCheckWithResult(userId);
    return result.completed() && !result.partialFailure();
  }

  public RefreshResult doCheckWithResult(String userId) {
    boolean successful = true;
    try {
      List<TrackedShow> shows = storage.loadAll(userId);
      log.debug("Daily update check for user {}: totalShows={}", userId, shows.size());

      Set<Long> updatedTmdb = Set.copyOf(metadata.fetchRecentlyUpdatedTmdbIds());
      Set<Long> updatedTvmaze = Set.copyOf(metadata.fetchRecentlyUpdatedTvmazeIds());

      log.debug("Updated TMDB ids count: {}, TVMaze ids count: {}", updatedTmdb.size(),
          updatedTvmaze.size());

      for (TrackedShow show : shows) {
        // Always refresh all shows for a full refresh per user request
        boolean matchTmdb = show.tmdbId != null && updatedTmdb.contains(show.tmdbId);
        boolean matchTvmaze = show.tvmazeId != null && updatedTvmaze.contains(show.tvmazeId);

        // If the stored show has empty seasons (placeholders), note it for logs
        boolean hasEmptySeason = show.seasons != null && show.seasons.stream()
            .anyMatch(s -> s.episodes == null || s.episodes.isEmpty());
        if (hasEmptySeason) {
          log.debug("Checking show '{}' has empty seasons — will refresh", show.title);
        }

        log.debug(
            "Checking show '{}' (tmdbId={}, tvmazeId={}) matchTmdb={} matchTvmaze={} hasEmptySeason={}",
            show.title, show.tmdbId, show.tvmazeId, matchTmdb, matchTvmaze, hasEmptySeason);

        try {
          if (show.tmdbId == null) {
            metadata.hydrateMissingTmdbId(show);
          }
          TrackedShow fresh = metadata.fetchDetails(show.tmdbId, show.tvmazeId);
          boolean hasNewEpisodes = mergeNewEpisodes(show, fresh);
          boolean statusChanged = false;
          if (hasNewEpisodes) {
            show.watchStatus = WatchStatus.WATCHING_NOW;
            statusChanged = true;
            log.debug("Show '{}' moved to WATCHING_NOW (new episodes added)", show.title);
          } else {
            log.debug("Show '{}' was updated but has no new episodes — keeping UP_TO_DATE",
                show.title);
          }
          // Refresh ratings (OMDb) â€” skips if fresh or not configured
          metadata.enrichRatings(show);
          storage.save(userId, show, statusChanged);
        } catch (Exception e) {
          successful = false;
          log.warn("Failed to refresh show '{}': {}", show.title, e.getMessage());
        }
      }

      // Heal any WATCHING_NOW shows where all episodes are already watched
      // (e.g. stuck there from a previous bug or an empty-season false-positive)
      for (TrackedShow show : shows) {
        if (show.watchStatus != WatchStatus.WATCHING_NOW) {
          continue;
        }
        WatchStatus before = show.watchStatus;
        show.recalculateStatus();
        if (show.watchStatus != before) {
          storage.save(userId, show, true);
          log.debug("Show '{}' healed: {} → {}", show.title, before, show.watchStatus);
        }
      }
      return new RefreshResult(true, !successful);
    } catch (Exception e) {
      log.error("Daily update check failed for user {}", userId, e);
      return new RefreshResult(false, false);
    }
  }

  public record RefreshResult(boolean completed, boolean partialFailure) {}

  private static boolean hasRatingValue(String rating) {
    return rating != null && !rating.isBlank() && !"N/A".equalsIgnoreCase(rating);
  }

  /**
   * Merges fresh metadata into existing show. Returns true if any new episodes were added.
   */
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
          var copy = new Season(freshSeason.number);
          copy.episodes = new ArrayList<>();
          for (var ep : freshSeason.episodes) {
            if (ep.number == 0) {
              continue;
            }
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
          if (freshEp.number == 0) {
            continue;
          }
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
