package com.tvtracker.storage;

import com.tvtracker.model.*;
import com.tvtracker.provider.MetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final MetadataService metadata;

    public ImportService(MetadataService metadata) {
        this.metadata = metadata;
    }

    /**
     * Converts a human-friendly payload into fully-resolved TrackedShow list.
     * Shows that can't be resolved via the metadata API are added with title-only stubs.
     */
    public record ImportResult(List<TrackedShow> shows, List<String> stubTitles) {}

    public ImportResult resolve(ImportExportPayload payload) {
        List<TrackedShow> result = new ArrayList<>();
        List<String> stubs = new ArrayList<>();

        addAll(result, stubs, payload.shows,         WatchStatus.WATCHING_NOW);
        addAll(result, stubs, payload.watchlistShows, WatchStatus.NOT_WATCHED);
        addAll(result, stubs, payload.upToDateShows,  WatchStatus.UP_TO_DATE);
        addAll(result, stubs, payload.finishedShows,  WatchStatus.FINISHED);
        addAll(result, stubs, payload.stoppedShows,   WatchStatus.DROPPED);

        return new ImportResult(result, stubs);
    }

    private void addAll(List<TrackedShow> result, List<String> stubs,
                        List<ImportExportPayload.ImportShow> imports,
                        WatchStatus targetStatus) {
        if (imports == null) return;
        for (var imp : imports) {
            try {
                TrackedShow show = fetchFresh(imp);
                overlayWatchedState(show, imp);
                show.watchStatus = targetStatus;
                result.add(show);
            } catch (Exception e) {
                log.warn("Could not resolve show '{}': {} — adding as stub", imp.title, e.getMessage());
                stubs.add(imp.title);
                result.add(stub(imp, targetStatus));
            }
        }
    }

    /** Fetch fresh metadata from API using stored IDs, falling back to title search. */
    private TrackedShow fetchFresh(ImportExportPayload.ImportShow imp) {
        // Prefer direct ID lookup — fastest and most accurate
        if (imp.tmdbId != null || imp.tvmazeId != null) {
            TrackedShow show = metadata.fetchDetails(imp.tmdbId, imp.tvmazeId);
            show.id = UUID.randomUUID().toString();
            return show;
        }
        // Fall back to title search for hand-written imports
        return resolveFromApi(imp);
    }

    /** Overlay watched flags from the export onto the freshly-fetched show. */
    private void overlayWatchedState(TrackedShow show, ImportExportPayload.ImportShow imp) {
        if (imp.seasons == null || imp.seasons.isEmpty()) {
            if (imp.watchedSeasons != null) markWatchedSeasons(show, imp.watchedSeasons);
            return;
        }
        // Build lookup: seasonNum -> episodeNum -> watched
        Map<Integer, Map<Integer, Boolean>> watchedMap = new HashMap<>();
        for (var sd : imp.seasons) {
            if (sd.episodes == null) continue;
            Map<Integer, Boolean> epMap = new HashMap<>();
            for (var ed : sd.episodes) epMap.put(ed.number, ed.watched);
            watchedMap.put(sd.number, epMap);
        }
        for (Season season : show.seasons) {
            Map<Integer, Boolean> epMap = watchedMap.get(season.number);
            if (epMap == null) continue;
            for (Episode ep : season.episodes) {
                Boolean watched = epMap.get(ep.number);
                if (watched != null) ep.watched = watched;
            }
        }
    }

    private TrackedShow resolveFromApi(ImportExportPayload.ImportShow imp) {
        List<ShowSearchResult> hits = metadata.search(imp.title);
        ShowSearchResult best = hits.stream()
                .filter(h -> imp.year == null || matchesYear(h, imp.year))
                .findFirst()
                .orElse(hits.isEmpty() ? null : hits.getFirst());

        if (best == null) throw new RuntimeException("No search results");

        TrackedShow show = metadata.fetchDetails(best.tmdbId, best.tvmazeId);
        show.id = UUID.randomUUID().toString();
        return show;
    }

    private boolean matchesYear(ShowSearchResult r, int year) {
        // posterPath / overview don't carry year; we rely on title proximity only
        // TVMaze and TMDB both return results ordered by relevance, so first match is usually correct
        return true; // year is used as a hint for disambiguation only when needed
    }

    private void markWatchedSeasons(TrackedShow show, List<Integer> watchedSeasons) {
        Set<Integer> watched = new HashSet<>(watchedSeasons);
        for (Season season : show.seasons) {
            if (watched.contains(season.number)) {
                season.episodes.forEach(ep -> ep.watched = true);
            }
        }
    }

    private TrackedShow stub(ImportExportPayload.ImportShow imp, WatchStatus status) {
        TrackedShow show = new TrackedShow();
        show.id = UUID.randomUUID().toString();
        show.title = imp.title;
        show.watchStatus = status;
        return show;
    }

    /** Converts the current tracked shows back to the human-friendly export format. */
    public ImportExportPayload toPayload(List<TrackedShow> shows) {
        ImportExportPayload payload = new ImportExportPayload();
        payload.shows           = new ArrayList<>();
        payload.watchlistShows  = new ArrayList<>();
        payload.upToDateShows   = new ArrayList<>();
        payload.finishedShows   = new ArrayList<>();
        payload.stoppedShows    = new ArrayList<>();

        for (TrackedShow show : shows) {
            var entry = new ImportExportPayload.ImportShow();
            entry.title            = show.title;
            entry.tmdbId           = show.tmdbId;
            entry.tvmazeId         = show.tvmazeId;
            entry.imdbId           = show.imdbId;
            entry.posterPath       = show.posterPath;
            entry.overview         = show.overview;
            entry.productionStatus = show.productionStatus != null ? show.productionStatus.name() : null;
            entry.seasons          = toSeasonDetails(show.seasons);

            // watched_seasons kept for human readability
            List<Integer> watchedSeasons = show.seasons.stream()
                    .filter(Season::allWatched).map(s -> s.number).toList();

            switch (show.watchStatus) {
                case WATCHING_NOW -> {
                    entry.watchedSeasons = watchedSeasons.isEmpty() ? null : watchedSeasons;
                    payload.shows.add(entry);
                }
                case NOT_WATCHED -> payload.watchlistShows.add(entry);
                case UP_TO_DATE  -> payload.upToDateShows.add(entry);
                case FINISHED    -> payload.finishedShows.add(entry);
                case DROPPED     -> payload.stoppedShows.add(entry);
            }
        }
        return payload;
    }

    private List<ImportExportPayload.SeasonDetail> toSeasonDetails(List<Season> seasons) {
        return seasons.stream().map(s -> {
            var sd = new ImportExportPayload.SeasonDetail();
            sd.number = s.number;
            sd.episodes = s.episodes.stream().map(e -> {
                var ed = new ImportExportPayload.EpisodeDetail();
                ed.number  = e.number;
                ed.name    = e.name;
                ed.watched = e.watched;
                ed.airDate = e.airDate;
                return ed;
            }).toList();
            return sd;
        }).toList();
    }
}
