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
    public List<TrackedShow> resolve(ImportExportPayload payload) {
        List<TrackedShow> result = new ArrayList<>();

        addAll(result, payload.shows,        WatchStatus.WATCHING_NOW, true);
        addAll(result, payload.watchlistShows, WatchStatus.NOT_WATCHED, false);
        addAll(result, payload.upToDateShows,  WatchStatus.UP_TO_DATE,  false);
        addAll(result, payload.finishedShows,  WatchStatus.FINISHED,    false);
        addAll(result, payload.stoppedShows,   WatchStatus.DROPPED,     false);

        return result;
    }

    private void addAll(List<TrackedShow> result,
                        List<ImportExportPayload.ImportShow> imports,
                        WatchStatus targetStatus,
                        boolean applyWatchedSeasons) {
        if (imports == null) return;
        for (var imp : imports) {
            try {
                TrackedShow show = imp.seasons != null && !imp.seasons.isEmpty()
                        ? restoreFromDetail(imp)       // lossless round-trip path
                        : resolveFromApi(imp);         // hand-written import path

                if (applyWatchedSeasons && imp.watchedSeasons != null && (imp.seasons == null || imp.seasons.isEmpty())) {
                    markWatchedSeasons(show, imp.watchedSeasons);
                }

                if (targetStatus == WatchStatus.UP_TO_DATE
                        || targetStatus == WatchStatus.FINISHED
                        || targetStatus == WatchStatus.DROPPED
                        || targetStatus == WatchStatus.NOT_WATCHED) {
                    show.watchStatus = targetStatus;
                } else {
                    show.recalculateStatus();
                }
                result.add(show);
            } catch (Exception e) {
                log.warn("Could not resolve show '{}' ({}): {} — adding as stub", imp.title, imp.year, e.getMessage());
                result.add(stub(imp, targetStatus));
            }
        }
    }

    /** Restore a show directly from the embedded season/episode detail — no API call needed. */
    private TrackedShow restoreFromDetail(ImportExportPayload.ImportShow imp) {
        TrackedShow show = new TrackedShow();
        show.id = UUID.randomUUID().toString();
        show.title = imp.title;
        show.seasons = new ArrayList<>();
        for (var sd : imp.seasons) {
            Season season = new Season(sd.number);
            if (sd.episodes != null) {
                for (var ed : sd.episodes) {
                    Episode ep = new Episode(ed.number, ed.name, ed.airDate);
                    ep.watched = ed.watched;
                    season.episodes.add(ep);
                }
            }
            show.seasons.add(season);
        }
        show.totalSeasons = show.seasons.size();
        return show;
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
            entry.title = show.title;
            entry.seasons = toSeasonDetails(show.seasons);

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
