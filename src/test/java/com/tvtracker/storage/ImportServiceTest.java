package com.tvtracker.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImportServiceTest {

  @Test
  void importUsesSourceSectionsAndAssignsNewIds() {
    ImportExportPayload payload = new ImportExportPayload();
    payload.shows = List.of(importShow("Watching", 1));
    payload.watchlistShows = List.of(importShow("Planned", 2));
    payload.upToDateShows = List.of(importShow("Up to date", 3));
    payload.finishedShows = List.of(importShow("Finished", 4));
    payload.stoppedShows = List.of(importShow("Stopped", 5));

    List<TrackedShow> imported = new ImportService().resolve(payload).shows();

    assertEquals(5, imported.size());
    assertEquals(List.of(
            WatchStatus.WATCHING_NOW,
            WatchStatus.NOT_WATCHED,
            WatchStatus.UP_TO_DATE,
            WatchStatus.FINISHED,
            WatchStatus.DROPPED),
        imported.stream().map(show -> show.watchStatus).toList());
    assertNotNull(imported.get(0).id);
    assertNotEquals(imported.get(0).id, imported.get(1).id);
  }

  @Test
  void importRestoresSnapshotMetadataAndRatingsWithoutResolution() {
    ImportExportPayload payload = new ImportExportPayload();
    ImportExportPayload.ImportShow entry = importShow("Silo", 1);
    entry.year = 2023;
    entry.totalSeasons = 2;
    entry.tmdbId = 42L;
    entry.tvmazeId = 24L;
    entry.imdbId = "tt1234567";
    entry.posterPath = "/poster.jpg";
    entry.overview = "Snapshot overview";
    entry.productionStatus = "ENDED";
    entry.imdbRating = "8.4";
    entry.rtRating = "95%";
    entry.ratingsUpdatedAt = "2026-10-01";
    entry.ratingLastFetched = "2026-10-02";
    entry.personalRating = 9.0;
    entry.cast = List.of(new Actor("Actor", "/actor.jpg", "https://example.test/actor"));
    entry.seasons.getFirst().imdbRating = "8.1";
    entry.seasons.getFirst().rtRating = "90%";
    entry.seasons.getFirst().ratingsUpdatedAt = "2026-10-01";
    entry.seasons.getFirst().episodes.getFirst().watched = true;
    payload.shows = List.of(entry);

    TrackedShow imported = new ImportService().resolve(payload).shows().getFirst();

    assertEquals(42L, imported.tmdbId);
    assertEquals(2023, imported.firstAirYear);
    assertEquals(2, imported.totalSeasons);
    assertEquals("tt1234567", imported.imdbId);
    assertEquals("/poster.jpg", imported.posterPath);
    assertEquals("Snapshot overview", imported.overview);
    assertEquals(ProductionStatus.ENDED, imported.productionStatus);
    assertEquals("8.4", imported.imdbRating);
    assertEquals("95%", imported.rtRating);
    assertEquals("2026-10-01", imported.ratingsUpdatedAt);
    assertEquals("2026-10-02", imported.ratingLastFetched);
    assertEquals(9.0, imported.personalRating);
    assertEquals("Actor", imported.cast.getFirst().name);
    assertEquals("8.1", imported.seasons.getFirst().imdbRating);
    assertEquals("90%", imported.seasons.getFirst().rtRating);
    assertTrue(imported.seasons.getFirst().episodes.getFirst().watched);
  }

  @Test
  void exportedShowRatingsAndDetailsRoundTripThroughImport() throws Exception {
    TrackedShow original = showWithSeasons("Silo", 1);
    original.watchStatus = WatchStatus.WATCHING_NOW;
    original.tmdbId = 42L;
    original.firstAirYear = 2023;
    original.totalSeasons = 2;
    original.imdbRating = "8.4";
    original.rtRating = "95%";
    original.ratingsUpdatedAt = "2026-10-01";
    original.ratingLastFetched = "2026-10-02";
    original.personalRating = 9.0;
    original.cast = new ArrayList<>(List.of(new Actor("Actor", "/actor.jpg", null)));
    Season season = original.seasons.getFirst();
    season.imdbRating = "8.1";
    season.rtRating = "90%";
    season.ratingsUpdatedAt = "2026-10-01";
    season.episodes.getFirst().watched = true;

    ObjectMapper mapper = new ObjectMapper();
    ImportExportPayload exported = new ImportService().toPayload(List.of(original));
    ImportExportPayload serialized = mapper.readValue(mapper.writeValueAsBytes(exported),
        ImportExportPayload.class);
    TrackedShow restored = new ImportService().resolve(serialized).shows().getFirst();

    assertEquals(original.imdbRating, restored.imdbRating);
    assertEquals(original.rtRating, restored.rtRating);
    assertEquals(original.ratingsUpdatedAt, restored.ratingsUpdatedAt);
    assertEquals(original.ratingLastFetched, restored.ratingLastFetched);
    assertEquals(original.personalRating, restored.personalRating);
    assertEquals(original.cast.getFirst().name, restored.cast.getFirst().name);
    assertEquals(season.imdbRating, restored.seasons.getFirst().imdbRating);
    assertEquals(season.rtRating, restored.seasons.getFirst().rtRating);
    assertEquals(season.ratingsUpdatedAt, restored.seasons.getFirst().ratingsUpdatedAt);
    assertTrue(restored.seasons.getFirst().episodes.getFirst().watched);
  }

  @Test
  void legacyWatchedSeasonsAreAppliedWhenEpisodeDetailsAreMissing() {
    ImportExportPayload payload = new ImportExportPayload();
    ImportExportPayload.ImportShow entry = new ImportExportPayload.ImportShow();
    entry.title = "Legacy show";
    entry.watchedSeasons = List.of(1);
    payload.shows = List.of(entry);

    TrackedShow imported = new ImportService().resolve(payload).shows().getFirst();

    assertEquals("Legacy show", imported.title);
    assertTrue(imported.seasons.isEmpty());
    assertEquals(WatchStatus.WATCHING_NOW, imported.watchStatus);
  }

  @Test
  void exportRoutesShowsToTheirSections() {
    TrackedShow watching = showWithSeasons("Watching", 1);
    watching.watchStatus = WatchStatus.WATCHING_NOW;
    watching.seasons.getFirst().episodes.forEach(episode -> episode.watched = true);
    TrackedShow planned = showWithSeasons("Planned", 1);
    planned.watchStatus = WatchStatus.NOT_WATCHED;
    TrackedShow finished = showWithSeasons("Finished", 1);
    finished.watchStatus = WatchStatus.FINISHED;
    TrackedShow dropped = showWithSeasons("Dropped", 1);
    dropped.watchStatus = WatchStatus.DROPPED;
    TrackedShow upToDate = showWithSeasons("Up to date", 1);
    upToDate.watchStatus = WatchStatus.UP_TO_DATE;

    ImportExportPayload payload = new ImportService().toPayload(
        List.of(watching, planned, finished, dropped, upToDate));

    assertEquals("Watching", payload.shows.getFirst().title);
    assertEquals(List.of(1), payload.shows.getFirst().watchedSeasons);
    assertEquals("Planned", payload.watchlistShows.getFirst().title);
    assertEquals("Finished", payload.finishedShows.getFirst().title);
    assertEquals("Dropped", payload.stoppedShows.getFirst().title);
    assertEquals("Up to date", payload.upToDateShows.getFirst().title);
  }

  private static ImportExportPayload.ImportShow importShow(String title, int seasonNumber) {
    ImportExportPayload.ImportShow show = new ImportExportPayload.ImportShow();
    show.title = title;
    ImportExportPayload.SeasonDetail season = new ImportExportPayload.SeasonDetail();
    season.number = seasonNumber;
    season.episodes = new ArrayList<>();
    ImportExportPayload.EpisodeDetail episode = new ImportExportPayload.EpisodeDetail();
    episode.number = 1;
    episode.name = "Episode 1";
    season.episodes.add(episode);
    show.seasons = new ArrayList<>(List.of(season));
    return show;
  }

  private static TrackedShow showWithSeasons(String title, int... seasonNumbers) {
    TrackedShow show = new TrackedShow();
    show.title = title;
    show.productionStatus = ProductionStatus.ONGOING;
    show.watchStatus = WatchStatus.NOT_WATCHED;
    show.seasons = new ArrayList<>();
    for (int number : seasonNumbers) {
      Season season = new Season(number);
      season.episodes = new ArrayList<>(List.of(
          new Episode(1, "E1", null),
          new Episode(2, "E2", null)));
      show.seasons.add(season);
    }
    return show;
  }
}
