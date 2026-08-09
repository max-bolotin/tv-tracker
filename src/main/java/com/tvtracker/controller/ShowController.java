package com.tvtracker.controller;

import com.tvtracker.exception.ShowNotFoundException;
import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.model.WatchStatus;
import com.tvtracker.provider.MetadataService;
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

    public ShowController(JsonStorageService storage, MetadataService metadata) {
        this.storage = storage;
        this.metadata = metadata;
    }

    @GetMapping
    public List<TrackedShow> getAll(@RequestParam(required = false) WatchStatus status) throws IOException {
        List<TrackedShow> all = storage.loadAll();
        if (status != null) return all.stream().filter(s -> s.watchStatus == status).toList();
        return all;
    }

    @GetMapping("/{id}")
    public TrackedShow getOne(@PathVariable String id) throws IOException {
        return storage.findById(id).orElseThrow(() -> new ShowNotFoundException(id));
    }

    @GetMapping("/search")
    public List<ShowSearchResult> search(@RequestParam String q) {
        return metadata.search(q);
    }

    /** Add a show to tracking by fetching its metadata from external API */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PostMapping
    public TrackedShow addShow(@RequestBody AddShowRequest req) throws IOException {
        TrackedShow show = metadata.fetchDetails(req.tmdbId(), req.tvmazeId());
        show.id = UUID.randomUUID().toString();
        show.watchStatus = WatchStatus.NOT_WATCHED;
        return storage.save(show);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        if (!storage.delete(id)) throw new ShowNotFoundException(id);
        return ResponseEntity.noContent().build();
    }

    /** Update watch status manually (e.g. mark as DROPPED) */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PatchMapping("/{id}/status")
    public TrackedShow updateStatus(@PathVariable String id, @RequestBody StatusUpdate body) throws IOException {
        TrackedShow show = storage.findById(id).orElseThrow(() -> new ShowNotFoundException(id));
        show.watchStatus = body.status();
        return storage.save(show);
    }

    /** Toggle watched state for a specific episode */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PatchMapping("/{id}/seasons/{season}/episodes/{episode}")
    public TrackedShow toggleEpisode(
            @PathVariable String id,
            @PathVariable int season,
            @PathVariable int episode,
            @RequestBody EpisodeToggle body) throws IOException {

        TrackedShow show = storage.findById(id).orElseThrow(() -> new ShowNotFoundException(id));
        show.seasons.stream()
            .filter(s -> s.number == season).findFirst()
            .flatMap(s -> s.episodes.stream().filter(e -> e.number == episode).findFirst())
            .ifPresent(ep -> ep.watched = body.watched());
        show.recalculateStatus();
        return storage.save(show);
    }

    /** Toggle all episodes in a season */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PatchMapping("/{id}/seasons/{season}")
    public TrackedShow toggleSeason(
            @PathVariable String id,
            @PathVariable int season,
            @RequestBody EpisodeToggle body) throws IOException {

        TrackedShow show = storage.findById(id).orElseThrow(() -> new ShowNotFoundException(id));
        show.seasons.stream().filter(s -> s.number == season).findFirst()
            .ifPresent(s -> s.episodes.forEach(ep -> ep.watched = body.watched()));
        show.recalculateStatus();
        return storage.save(show);
    }

    /** Toggle all episodes across every season */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PatchMapping("/{id}/watched")
    public TrackedShow toggleAllWatched(
            @PathVariable String id,
            @RequestBody EpisodeToggle body) throws IOException {

        TrackedShow show = storage.findById(id).orElseThrow(() -> new ShowNotFoundException(id));
        show.seasons.forEach(s -> s.episodes.forEach(ep -> ep.watched = body.watched()));
        show.recalculateStatus();
        return storage.save(show);
    }

    /** Persist a new display order given an ordered list of show IDs */
    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody List<String> orderedIds) throws IOException {
        storage.reorder(orderedIds);
        return ResponseEntity.noContent().build();
    }

   @PostMapping("/{id}/refresh")
    public TrackedShow refreshShow(@PathVariable String id) throws IOException {
        TrackedShow existing = storage.findById(id).orElseThrow(() -> new ShowNotFoundException(id));
        TrackedShow fresh = metadata.fetchDetails(existing.tmdbId, existing.tvmazeId);
        fresh.id = existing.id;
        fresh.watchStatus = existing.watchStatus;
        for (var existingSeason : existing.seasons) {
            fresh.seasons.stream().filter(s -> s.number == existingSeason.number).findFirst()
                .ifPresent(freshSeason -> existingSeason.episodes.forEach(existingEp ->
                    freshSeason.episodes.stream().filter(e -> e.number == existingEp.number).findFirst()
                        .ifPresent(freshEp -> freshEp.watched = existingEp.watched)));
        }
        return storage.save(fresh);
    }

    private record AddShowRequest(Long tmdbId, Long tvmazeId) {}
    private record StatusUpdate(WatchStatus status) {}
    private record EpisodeToggle(boolean watched) {}
}
