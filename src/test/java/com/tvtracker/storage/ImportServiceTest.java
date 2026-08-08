package com.tvtracker.storage;

import com.tvtracker.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests ImportService business logic: payload → TrackedShow mapping,
 * watched-season marking, and round-trip export shape.
 * No metadata API calls — uses a stub that returns pre-built shows.
 */
class ImportServiceTest {

    // --- stub MetadataService that returns a show built from the search title ---
    private static com.tvtracker.provider.MetadataService stubMetadata(TrackedShow... returnedShows) {
        return new com.tvtracker.provider.MetadataService(null, null) {
            int callCount = 0;

            @Override
            public List<ShowSearchResult> search(String query) {
                ShowSearchResult r = new ShowSearchResult();
                r.title = query;
                // no tmdbId/tvmazeId — forces resolveFromApi path which calls fetchDetails next
                return List.of(r);
            }

            @Override
            public TrackedShow fetchDetails(Long tmdbId, Long tvmazeId) {
                if (returnedShows.length == 0) throw new RuntimeException("No stub shows");
                TrackedShow show = returnedShows[Math.min(callCount++, returnedShows.length - 1)];
                show.id = null;
                return show;
            }
        };
    }

    private static TrackedShow showWithSeasons(String title, int... seasonNumbers) {
        TrackedShow show = new TrackedShow();
        show.title = title;
        show.productionStatus = ProductionStatus.ONGOING;
        show.watchStatus = WatchStatus.NOT_WATCHED;
        show.seasons = new java.util.ArrayList<>();
        for (int n : seasonNumbers) {
            Season s = new Season(n);
            s.episodes = new java.util.ArrayList<>(List.of(
                    new Episode(1, "E1", null),
                    new Episode(2, "E2", null)
            ));
            show.seasons.add(s);
        }
        return show;
    }

    // --- import tests ---

    @Test
    void watchlistShow_isImportedAsNotWatched() {
        TrackedShow fetched = showWithSeasons("Firefly", 1);
        ImportService service = new ImportService(stubMetadata(fetched));

        ImportExportPayload payload = new ImportExportPayload();
        var entry = new ImportExportPayload.ImportShow();
        entry.title = "Firefly";
        payload.watchlistShows = List.of(entry);

        List<TrackedShow> result = service.resolve(payload).shows();

        assertEquals(1, result.size());
        assertEquals(WatchStatus.NOT_WATCHED, result.getFirst().watchStatus);
    }

    @Test
    void watchedSeasonsAreMarkedCorrectly() {
        TrackedShow fetched = showWithSeasons("Doctor Who", 1, 2, 3);
        ImportService service = new ImportService(stubMetadata(fetched));

        ImportExportPayload payload = new ImportExportPayload();
        var entry = new ImportExportPayload.ImportShow();
        entry.title = "Doctor Who";
        entry.watchedSeasons = List.of(1, 2);
        payload.shows = List.of(entry);

        List<TrackedShow> result = service.resolve(payload).shows();
        TrackedShow show = result.getFirst();

        assertTrue(show.seasons.get(0).allWatched(), "Season 1 should be fully watched");
        assertTrue(show.seasons.get(1).allWatched(), "Season 2 should be fully watched");
        assertFalse(show.seasons.get(2).allWatched(), "Season 3 should NOT be watched");
    }

    @Test
    void partiallyWatchedImport_statusIsWatchingNow() {
        TrackedShow fetched = showWithSeasons("Silo", 1, 2);
        ImportService service = new ImportService(stubMetadata(fetched));

        ImportExportPayload payload = new ImportExportPayload();
        var entry = new ImportExportPayload.ImportShow();
        entry.title = "Silo";
        entry.watchedSeasons = List.of(1); // season 2 not watched
        payload.shows = List.of(entry);

        List<TrackedShow> result = service.resolve(payload).shows();
        assertEquals(WatchStatus.WATCHING_NOW, result.getFirst().watchStatus);
    }

    @Test
    void finishedShow_isImportedWithFinishedStatus() {
        TrackedShow fetched = showWithSeasons("Good Omens", 1, 2);
        ImportService service = new ImportService(stubMetadata(fetched));

        ImportExportPayload payload = new ImportExportPayload();
        var entry = new ImportExportPayload.ImportShow();
        entry.title = "Good Omens";
        payload.finishedShows = List.of(entry);

        List<TrackedShow> result = service.resolve(payload).shows();
        assertEquals(WatchStatus.FINISHED, result.getFirst().watchStatus);
    }

    @Test
    void stoppedShow_isImportedAsDropped() {
        TrackedShow fetched = showWithSeasons("The Recruit", 1);
        ImportService service = new ImportService(stubMetadata(fetched));

        ImportExportPayload payload = new ImportExportPayload();
        var entry = new ImportExportPayload.ImportShow();
        entry.title = "The Recruit";
        payload.stoppedShows = List.of(entry);

        List<TrackedShow> result = service.resolve(payload).shows();
        assertEquals(WatchStatus.DROPPED, result.getFirst().watchStatus);
    }

    @Test
    void eachImportedShowGetsUniqueId() {
        TrackedShow a = showWithSeasons("Show A", 1);
        TrackedShow b = showWithSeasons("Show B", 1);
        ImportService service = new ImportService(stubMetadata(a, b));

        ImportExportPayload payload = new ImportExportPayload();
        var e1 = new ImportExportPayload.ImportShow(); e1.title = "Show A";
        var e2 = new ImportExportPayload.ImportShow(); e2.title = "Show B";
        payload.watchlistShows = List.of(e1, e2);

        List<TrackedShow> result = service.resolve(payload).shows();
        assertNotEquals(result.get(0).id, result.get(1).id);
    }

    @Test
    void nullSections_areSkippedGracefully() {
        ImportService service = new ImportService(stubMetadata());
        ImportExportPayload payload = new ImportExportPayload();
        // all lists are null — should not throw
        assertDoesNotThrow(() -> service.resolve(payload));
        assertTrue(service.resolve(payload).shows().isEmpty());
    }

    // --- export (toPayload) tests ---

    @Test
    void export_routesShowsToCorrectSection() {
        TrackedShow watching = showWithSeasons("Silo", 1, 2);
        watching.watchStatus = WatchStatus.WATCHING_NOW;
        watching.seasons.getFirst().episodes.forEach(e -> e.watched = true);

        TrackedShow notWatched = showWithSeasons("Firefly", 1);
        notWatched.watchStatus = WatchStatus.NOT_WATCHED;

        TrackedShow finished = showWithSeasons("Good Omens", 1);
        finished.watchStatus = WatchStatus.FINISHED;

        TrackedShow dropped = showWithSeasons("The Recruit", 1);
        dropped.watchStatus = WatchStatus.DROPPED;

        TrackedShow upToDate = showWithSeasons("Black Mirror", 1);
        upToDate.watchStatus = WatchStatus.UP_TO_DATE;

        ImportService service = new ImportService(null); // toPayload doesn't use metadata
        ImportExportPayload payload = service.toPayload(List.of(watching, notWatched, finished, dropped, upToDate));

        assertEquals(1, payload.shows.size());
        assertEquals("Silo", payload.shows.getFirst().title);
        assertEquals(List.of(1), payload.shows.getFirst().watchedSeasons);

        assertEquals(1, payload.watchlistShows.size());
        assertEquals("Firefly", payload.watchlistShows.getFirst().title);

        assertEquals(1, payload.finishedShows.size());
        assertEquals(1, payload.stoppedShows.size());
        assertEquals(1, payload.upToDateShows.size());
    }

    @Test
    void export_watchingNowWithNoFullSeasons_hasNullWatchedSeasons() {
        TrackedShow show = showWithSeasons("Silo", 1, 2);
        show.watchStatus = WatchStatus.WATCHING_NOW;
        // only one episode in season 1 watched — season not fully done
        show.seasons.getFirst().episodes.getFirst().watched = true;

        ImportService service = new ImportService(null);
        ImportExportPayload payload = service.toPayload(List.of(show));

        assertNull(payload.shows.getFirst().watchedSeasons);
    }
}
