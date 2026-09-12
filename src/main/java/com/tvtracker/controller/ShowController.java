package com.tvtracker.controller;

import com.tvtracker.exception.ShowNotFoundException;
import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.model.WatchStatus;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.security.CurrentUserContext;
import com.tvtracker.storage.JsonStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/shows")
public class ShowController {

  private final JsonStorageService storage;
  private final MetadataService metadata;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(
      ShowController.class);

  public ShowController(JsonStorageService storage, MetadataService metadata) {
    this.storage = storage;
    this.metadata = metadata;
  }

  @GetMapping("/popular")
  public List<com.tvtracker.model.ShowSearchResult> popular(
      @RequestParam(required = false, defaultValue = "20") int limit) {
    return metadata.fetchPopular(limit);
  }

  @GetMapping
  public List<TrackedShow> getAll(@RequestParam(required = false) WatchStatus status)
      throws IOException {
    String userId = CurrentUserContext.currentUserId();
    List<TrackedShow> all = storage.loadAll(userId);
      if (status != null) {
          return all.stream().filter(s -> s.watchStatus == status).toList();
      }
    return all;
  }

  @GetMapping("/{id}")
  public TrackedShow getOne(@PathVariable String id) throws IOException {
    String userId = CurrentUserContext.currentUserId();
    return storage.findById(userId, id).orElseThrow(() -> new ShowNotFoundException(id));
  }

  @GetMapping("/search")
  public List<ShowSearchResult> search(@RequestParam String q) {
    return metadata.search(q);
  }

  /**
   * Fetch full show details from metadata provider WITHOUT persisting
   */
  @GetMapping("/details")
  public TrackedShow details(@RequestParam(required = false) Long tmdbId,
      @RequestParam(required = false) Long tvmazeId) {
    return metadata.fetchDetails(tmdbId, tvmazeId);
  }

  /**
   * Add a show to tracking by fetching its metadata from external API
   */
  @SuppressWarnings("ClassEscapesDefinedScope")
  @PostMapping
  public TrackedShow addShow(@RequestBody AddShowRequest req) throws IOException {
    String userId = CurrentUserContext.currentUserId();
    TrackedShow show = metadata.fetchDetails(req.tmdbId(), req.tvmazeId());
    show.id = UUID.randomUUID().toString();
    show.watchStatus = WatchStatus.NOT_WATCHED;
    return storage.save(userId, show, true);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
    String userId = CurrentUserContext.currentUserId();
      if (!storage.delete(userId, id)) {
          throw new ShowNotFoundException(id);
      }
    return ResponseEntity.noContent().build();
  }

  /**
   * Update watch status manually (e.g. mark as DROPPED)
   */
  @SuppressWarnings("ClassEscapesDefinedScope")
  @PatchMapping("/{id}/status")
  public TrackedShow updateStatus(@PathVariable String id, @RequestBody StatusUpdate body)
      throws IOException {
    String userId = CurrentUserContext.currentUserId();
    TrackedShow show = storage.findById(userId, id)
        .orElseThrow(() -> new ShowNotFoundException(id));
    WatchStatus before = show.watchStatus;
    show.watchStatus = body.status();
    return storage.save(userId, show, before != body.status());
  }

  @PatchMapping("/{id}/rating")
  public TrackedShow updatePersonalRating(@PathVariable String id, @RequestBody PersonalRatingUpdate body)
     throws IOException {
   String userId = CurrentUserContext.currentUserId();
   TrackedShow show = storage.findById(userId, id)
       .orElseThrow(() -> new ShowNotFoundException(id));
   show.personalRating = body.personalRating();
   return storage.save(userId, show, false);
  }

  /**
   * Toggle watched state for a specific episode
   */
  @SuppressWarnings("ClassEscapesDefinedScope")
  @PatchMapping("/{id}/seasons/{season}/episodes/{episode}")
  public TrackedShow toggleEpisode(
      @PathVariable String id,
      @PathVariable int season,
      @PathVariable int episode,
      @RequestBody EpisodeToggle body) throws IOException {

    String userId = CurrentUserContext.currentUserId();
    TrackedShow show = storage.findById(userId, id)
        .orElseThrow(() -> new ShowNotFoundException(id));
    WatchStatus beforeEp = show.watchStatus;
    show.seasons.stream()
        .filter(s -> s.number == season).findFirst()
        .flatMap(s -> s.episodes.stream().filter(e -> e.number == episode).findFirst())
        .ifPresent(ep -> ep.watched = body.watched());
    show.recalculateStatus();
    return storage.save(userId, show, show.watchStatus != beforeEp);
  }

  /**
   * Toggle all episodes in a season
   */
  @SuppressWarnings("ClassEscapesDefinedScope")
  @PatchMapping("/{id}/seasons/{season}")
  public TrackedShow toggleSeason(
      @PathVariable String id,
      @PathVariable int season,
      @RequestBody EpisodeToggle body) throws IOException {

    String userId = CurrentUserContext.currentUserId();
    TrackedShow show = storage.findById(userId, id)
        .orElseThrow(() -> new ShowNotFoundException(id));
    WatchStatus beforeSeason = show.watchStatus;
    show.seasons.stream().filter(s -> s.number == season).findFirst()
        .ifPresent(s -> s.episodes.forEach(ep -> ep.watched = body.watched()));
    show.recalculateStatus();
    return storage.save(userId, show, show.watchStatus != beforeSeason);
  }

  /**
   * Toggle all episodes across every season
   */
  @SuppressWarnings("ClassEscapesDefinedScope")
  @PatchMapping("/{id}/watched")
  public TrackedShow toggleAllWatched(
      @PathVariable String id,
      @RequestBody EpisodeToggle body) throws IOException {

    String userId = CurrentUserContext.currentUserId();
    TrackedShow show = storage.findById(userId, id)
        .orElseThrow(() -> new ShowNotFoundException(id));
    WatchStatus beforeAll = show.watchStatus;
    show.seasons.forEach(s -> s.episodes.forEach(ep -> ep.watched = body.watched()));
    show.recalculateStatus();
    return storage.save(userId, show, show.watchStatus != beforeAll);
  }

  /**
   * Persist a new display order given an ordered list of show IDs
   */
  @PutMapping("/reorder")
  public ResponseEntity<Void> reorder(@RequestBody List<String> orderedIds) throws IOException {
    String userId = CurrentUserContext.currentUserId();
    storage.reorder(userId, orderedIds);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/refresh")
  public TrackedShow refreshShow(@PathVariable String id) throws IOException {
    String userId = CurrentUserContext.currentUserId();
    TrackedShow existing = storage.findById(userId, id)
        .orElseThrow(() -> new ShowNotFoundException(id));
    if (existing.tmdbId == null) {
      metadata.hydrateMissingTmdbId(existing);
    }
    TrackedShow fresh = metadata.fetchDetails(existing.tmdbId, existing.tvmazeId);
    fresh.id = existing.id;
    fresh.watchStatus = existing.watchStatus;
    fresh.personalRating = existing.personalRating;
    if (fresh.cast == null) {
      fresh.cast = existing.cast != null ? existing.cast : new java.util.ArrayList<>();
    } else if (fresh.cast.isEmpty() && existing.cast != null && !existing.cast.isEmpty()) {
      long existingCastSize = existing.cast.size();
      long freshCastSize = fresh.cast.size();
      log.info("refreshShow: keeping existing cast for '{}' because refreshed cast is empty (existing={} fresh={})",
          existing.title, existingCastSize, freshCastSize);
      fresh.cast = existing.cast;
    }
    long existingCastCount = existing.cast == null ? 0L : (long) existing.cast.size();
    long freshCastCount = fresh.cast == null ? 0L : (long) fresh.cast.size();
    log.info("refreshShow: cast summary for '{}' - existing={} fresh={}", existing.title,
        existingCastCount, freshCastCount);
    // carry over watched flags for matching episodes
    for (var existingSeason : existing.seasons) {
      fresh.seasons.stream().filter(s -> s.number == existingSeason.number).findFirst()
          .ifPresent(freshSeason -> existingSeason.episodes.forEach(existingEp ->
              freshSeason.episodes.stream().filter(e -> e.number == existingEp.number).findFirst()
                  .ifPresent(freshEp -> freshEp.watched = existingEp.watched)));
    }
    // Business rule: if the show was UP_TO_DATE and we detected new seasons with episodes, move to WATCHING_NOW
    try {
      int maxExisting = existing.seasons.stream().mapToInt(s -> s.number).max().orElse(0);
      int maxFresh = fresh.seasons.stream().mapToInt(s -> s.number).max().orElse(0);
      boolean wasUpToDate = existing.watchStatus == WatchStatus.UP_TO_DATE;
      log.debug("refreshShow: {} maxExisting={}, maxFresh={}, wasUpToDate={}", existing.title,
          maxExisting, maxFresh, wasUpToDate);
      if (wasUpToDate) {
        // check whether any of the new seasons contain episodes OR any existing season was empty but now has episodes
        boolean newHasEpisodes = fresh.seasons.stream().anyMatch(fs -> {
          var esOpt = existing.seasons.stream().filter(s -> s.number == fs.number).findFirst();
          if (esOpt.isEmpty()) {
            return fs.episodes != null && !fs.episodes.isEmpty();
          } else {
            var es = esOpt.get();
            return (es.episodes == null || es.episodes.isEmpty()) && fs.episodes != null
                && !fs.episodes.isEmpty();
          }
        });
        log.debug("refreshShow: {} newHasEpisodes={}", existing.title, newHasEpisodes);
        if (newHasEpisodes) {
          fresh.watchStatus = WatchStatus.WATCHING_NOW;
          log.debug("refreshShow: {} moved to WATCHING_NOW (new episodes added)", existing.title);
        }
      }
    } catch (Exception e) {
      log.warn("refreshShow: failed to evaluate watch status change for {}: {}", existing.title,
          e.getMessage());
    }
    boolean statusChanged = fresh.watchStatus != existing.watchStatus;
    TrackedShow saved = storage.save(userId, fresh, statusChanged);
    log.debug("refreshShow: saved show {} with watchStatus={}", saved.title, saved.watchStatus);
    return saved;
  }

  record AddShowRequest(Long tmdbId, Long tvmazeId) {

  }

  record StatusUpdate(WatchStatus status) {

  }

  record PersonalRatingUpdate(Double personalRating) {

  }

  record EpisodeToggle(boolean watched) {

  }
}
