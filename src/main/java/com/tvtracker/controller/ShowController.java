package com.tvtracker.controller;

import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.model.WatchStatus;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.storage.JsonStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public List<TrackedShow> getAll(@RequestParam(required = false) WatchStatus status) throws Exception {
        List<TrackedShow> all = storage.loadAll();
        if (status != null) return all.stream().filter(s -> s.watchStatus == status).toList();
        return all;
    }

    @GetMapping("/{id}")
    public ResponseEntity<TrackedShow> getOne(@PathVariable String id) throws Exception {
        return storage.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public List<ShowSearchResult> search(@RequestParam String q) {
        return metadata.search(q);
    }

    /** Add a show to tracking by fetching its metadata from external API */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PostMapping
    public TrackedShow addShow(@RequestBody AddShowRequest req) throws Exception {
        TrackedShow show = metadata.fetchDetails(req.tmdbId(), req.tvmazeId());
        show.id = UUID.randomUUID().toString();
        show.watchStatus = WatchStatus.NOT_WATCHED;
        return storage.save(show);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws Exception {
        return storage.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Update watch status manually (e.g. mark as DROPPED) */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PatchMapping("/{id}/status")
    public ResponseEntity<TrackedShow> updateStatus(@PathVariable String id, @RequestBody StatusUpdate body) throws Exception {
        return storage.findById(id).map(show -> {
            show.watchStatus = body.status();
            try { return ResponseEntity.ok(storage.save(show)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Toggle watched state for a specific episode */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PatchMapping("/{id}/seasons/{season}/episodes/{episode}")
    public ResponseEntity<TrackedShow> toggleEpisode(
            @PathVariable String id,
            @PathVariable int season,
            @PathVariable int episode,
            @RequestBody EpisodeToggle body) throws Exception {

        return storage.findById(id).map(show -> {
            show.seasons.stream()
                .filter(s -> s.number == season).findFirst()
                .flatMap(s -> s.episodes.stream().filter(e -> e.number == episode).findFirst())
                .ifPresent(ep -> ep.watched = body.watched());
            show.recalculateStatus();
            try { return ResponseEntity.ok(storage.save(show)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Toggle all episodes in a season */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PatchMapping("/{id}/seasons/{season}")
    public ResponseEntity<TrackedShow> toggleSeason(
            @PathVariable String id,
            @PathVariable int season,
            @RequestBody EpisodeToggle body) throws Exception {

        return storage.findById(id).map(show -> {
            show.seasons.stream().filter(s -> s.number == season).findFirst()
                .ifPresent(s -> s.episodes.forEach(ep -> ep.watched = body.watched()));
            show.recalculateStatus();
            try { return ResponseEntity.ok(storage.save(show)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Toggle all episodes across every season */
    @SuppressWarnings("ClassEscapesDefinedScope")
    @PatchMapping("/{id}/watched")
    public ResponseEntity<TrackedShow> toggleAllWatched(
            @PathVariable String id,
            @RequestBody EpisodeToggle body) throws Exception {

        return storage.findById(id).map(show -> {
            show.seasons.forEach(s -> s.episodes.forEach(ep -> ep.watched = body.watched()));
            show.recalculateStatus();
            try { return ResponseEntity.ok(storage.save(show)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Persist a new display order given an ordered list of show IDs */
    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody List<String> orderedIds) throws Exception {
        storage.reorder(orderedIds);
        return ResponseEntity.noContent().build();
    }

    private record AddShowRequest(Long tmdbId, Long tvmazeId) {}
    private record StatusUpdate(WatchStatus status) {}
    private record EpisodeToggle(boolean watched) {}
}
