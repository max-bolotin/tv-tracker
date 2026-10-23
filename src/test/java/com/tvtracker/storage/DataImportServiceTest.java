package com.tvtracker.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.controller.SseController;
import com.tvtracker.exception.InvalidImportException;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.MovieStatus;
import com.tvtracker.model.ImportExportPayload;
import com.tvtracker.model.ProductionStatus;
import com.tvtracker.model.Season;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.model.WatchStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DataImportServiceTest {

  @TempDir
  Path tempDir;

  private ObjectMapper mapper;
  private JsonStorageService showStorage;
  private MovieStorageService movieStorage;
  private DataImportService importer;

  @BeforeEach
  void setUp() throws IOException {
    mapper = new ObjectMapper().findAndRegisterModules();
    SseController sse = mock(SseController.class);
    showStorage = new JsonStorageService(tempDir.resolve("users").toString(), mapper, sse);
    movieStorage = new MovieStorageService(
        tempDir.resolve("users").toString(),
        tempDir.resolve("movies").toString(),
        mapper,
        sse);
    importer = new DataImportService(mapper, new ImportService(), showStorage, movieStorage, sse);
  }

  @Test
  void legacyShowsOnlyImportLeavesMoviesUntouched() throws Exception {
    MovieEntry existingMovie = movie(11L, "Existing movie");
    movieStorage.save("alice", existingMovie);
    byte[] oldMovieFile = Files.readAllBytes(tempDir.resolve("movies").resolve("alice.json"));

    DataImportService.ImportSummary result = importJson("{\"shows\":[]}", "alice");

    assertEquals(0, result.showsImported());
    assertEquals(0, result.moviesImported());
    assertEquals("Existing movie", movieStorage.load("alice").getFirst().title);
    assertArrayEquals(oldMovieFile,
        Files.readAllBytes(tempDir.resolve("movies").resolve("alice.json")));
  }

  @Test
  void v2RoundTripImportsMoviesAndClearsRefreshTimestamp() throws Exception {
    String payload = """
        {
          "version": 2,
          "shows": [],
          "watchlist_shows": [],
          "up_to_date_shows": [],
          "finished_shows": [],
          "stopped_shows": [],
          "movies": [{
            "id": " ",
            "tmdbId": 42,
            "mediaType": "movie",
            "title": "Arrival",
            "status": "WATCHED",
            "watchedDate": "2026-10-20",
            "personalRating": 8.5,
            "cast": null,
            "lastRefreshedAt": "not-an-instant"
          }]
        }
        """;

    DataImportService.ImportSummary result = importJson(payload, "alice");

    MovieEntry imported = movieStorage.load("alice").getFirst();
    assertEquals(0, result.showsImported());
    assertEquals(1, result.moviesImported());
    assertEquals("Arrival", imported.title);
    assertEquals(MovieStatus.WATCHED, imported.status);
    assertEquals("2026-10-20", imported.watchedDate);
    assertEquals(8.5, imported.personalRating);
    assertTrue(imported.cast.isEmpty());
    assertNull(imported.lastRefreshedAt);
    assertEquals(imported.id, UUID.fromString(imported.id).toString());
  }

  @Test
  void importRegeneratesAnExistingIdWhenItWouldIdentifyADifferentMovie() throws Exception {
    MovieEntry existing = movie(7L, "Existing movie");
    existing.id = "stable-id";
    movieStorage.save("alice", existing);

    importJson("""
        {"version":2,"movies":[{"id":"stable-id","tmdbId":99,"title":"Replacement movie"}]}
        """, "alice");

    MovieEntry imported = movieStorage.load("alice").getFirst();
    assertEquals(99L, imported.tmdbId);
    assertTrue(!"stable-id".equals(imported.id));
    assertEquals(imported.id, UUID.fromString(imported.id).toString());
  }

  @Test
  void importRegeneratesMovieIdsThatAreUnsafeForUrlPaths() throws Exception {
    importJson("""
        {"version":2,"movies":[
          {"id":"a/b","tmdbId":100,"title":"Slash"},
          {"id":"a%2Fb","tmdbId":101,"title":"Encoded slash"},
          {"id":"a;b","tmdbId":102,"title":"Semicolon"}
        ]}
        """, "alice");

    List<MovieEntry> imported = movieStorage.load("alice");
    assertEquals(3, imported.size());
    for (MovieEntry movie : imported) {
      assertTrue(movie.id.matches("[A-Za-z0-9_-]{1,64}"));
      assertEquals(movie.id, UUID.fromString(movie.id).toString());
    }
  }

  @Test
  void nullMovieStatusUsesThePlannedDefault() throws Exception {
    MovieEntry movie = mapper.readValue("{\"status\":null}", MovieEntry.class);

    assertEquals(MovieStatus.PLANNED, movie.status);
  }

  @Test
  void exportedV2PayloadRoundTripsBothShowsAndMovies() throws Exception {
    TrackedShow originalShow = show("Arrival: The Series", WatchStatus.WATCHING_NOW);
    originalShow.seasons = List.of();
    ImportExportPayload payload = new ImportService().toPayload(List.of(originalShow));
    payload.version = 2;
    payload.movies = List.of(movie(42L, "Arrival"));

    importJson(mapper.writeValueAsString(payload), "alice");

    List<TrackedShow> importedShows = showStorage.loadForImport("alice");
    List<MovieEntry> importedMovies = movieStorage.load("alice");
    assertEquals(1, importedShows.size());
    assertEquals("Arrival: The Series", importedShows.getFirst().title);
    assertEquals(WatchStatus.WATCHING_NOW, importedShows.getFirst().watchStatus);
    assertEquals(1, importedMovies.size());
    assertEquals("Arrival", importedMovies.getFirst().title);
  }

  @Test
  void nonEmptyMultiSectionImportWritesShowsToTheirSourceSectionsAndReportsWrittenCount()
      throws Exception {
    DataImportService.ImportSummary result = importJson("""
        {
          "shows":[{"title":"Watching","tmdb_id":101}],
          "watchlist_shows":[{"title":"Planned","tmdb_id":102}],
          "up_to_date_shows":[{"title":"Current","tmdb_id":103}],
          "finished_shows":[{"title":"Finished","tmdb_id":104}],
          "stopped_shows":[{"title":"Stopped","tmdb_id":105}]
        }
        """, "alice");

    List<TrackedShow> saved = showStorage.loadForImport("alice");
    assertEquals(5, result.showsImported());
    assertEquals(5, saved.size());
    assertEquals(WatchStatus.WATCHING_NOW, find(saved, 101L).watchStatus);
    assertEquals(WatchStatus.NOT_WATCHED, find(saved, 102L).watchStatus);
    assertEquals(WatchStatus.UP_TO_DATE, find(saved, 103L).watchStatus);
    assertEquals(WatchStatus.FINISHED, find(saved, 104L).watchStatus);
    assertEquals(WatchStatus.DROPPED, find(saved, 105L).watchStatus);
  }

  @Test
  void partialImportRemovesExistingDuplicateTmdbIdEvenFromOmittedSection() throws Exception {
    TrackedShow existing = show("Old status", WatchStatus.FINISHED);
    existing.tmdbId = 201L;
    showStorage.saveAll("alice", List.of(existing));

    DataImportService.ImportSummary result = importJson(
        "{\"shows\":[{\"title\":\"New status\",\"tmdb_id\":201}]}", "alice");

    List<TrackedShow> saved = showStorage.loadForImport("alice");
    assertEquals(1, result.showsImported());
    assertEquals(1, saved.size());
    assertEquals("New status", saved.getFirst().title);
    assertEquals(WatchStatus.WATCHING_NOW, saved.getFirst().watchStatus);
  }

  @Test
  void duplicateMovieIdsAreRejectedWithoutWriting() throws Exception {
    movieStorage.save("alice", movie(31L, "Existing"));
    byte[] original = Files.readAllBytes(tempDir.resolve("movies").resolve("alice.json"));

    assertThrows(InvalidImportException.class, () -> importJson("""
        {"version":2,"movies":[
          {"id":"same-id","tmdbId":41},
          {"id":"same-id","tmdbId":42}
        ]}
        """, "alice"));

    assertArrayEquals(original,
        Files.readAllBytes(tempDir.resolve("movies").resolve("alice.json")));
  }

  @Test
  void invalidWatchedDateIsRejected() {
    assertThrows(InvalidImportException.class, () -> importJson("""
        {"version":2,"movies":[
          {"tmdbId":55,"status":"WATCHED","watchedDate":"2026-02-30"}
        ]}
        """, "alice"));
  }

  @Test
  void stringVersionTopLevelArrayAndNonArrayShowSectionAreRejected() {
    for (String invalid : List.of(
        "{\"version\":\"2\",\"movies\":[]}",
        "[]",
        "{\"shows\":{}}")) {
      assertThrows(InvalidImportException.class, () -> importJson(invalid, "alice"));
    }
  }

  @Test
  void legacyExportFixtureImportsWithoutChangingTheLegacyPayloadShape() throws Exception {
    try (var fixture = getClass().getResourceAsStream("/fixtures/legacy-export.json")) {
      assertNotNull(fixture);
      DataImportService.ImportSummary result = importer.importData(fixture, "alice");
      assertEquals(1, result.showsImported());
      assertEquals("Legacy fixture show",
          showStorage.loadForImport("alice").getFirst().title);
    }
  }

  @Test
  void listUserIdsIgnoresBackupAndTemporaryFiles() throws Exception {
    Path users = tempDir.resolve("users");
    Files.writeString(users.resolve("alice.json"), "[]");
    Files.writeString(users.resolve("alice.json.bak"), "[]");
    Files.writeString(users.resolve("alice.json.550e8400-e29b-41d4-a716-446655440000.tmp"), "[]");

    assertEquals(java.util.Set.of("default", "alice"),
        new java.util.HashSet<>(showStorage.listUserIds()));
  }

  @Test
  void importRestoresRatingsAndDetailsFromThePayloadWithoutMetadataProviders() throws Exception {
    importer.importData(new ByteArrayInputStream("""
        {"shows":[{
          "title":"Ratings show",
          "tmdb_id":301,
          "year":2022,
          "total_seasons":1,
          "poster_path":"/poster.jpg",
          "overview":"Saved overview",
          "production_status":"ENDED",
          "cast":[{"name":"Actor","profilePath":"/actor.jpg"}],
          "imdb_rating":"8.7",
          "rt_rating":"91%",
          "ratings_updated_at":"2026-10-01",
          "personal_rating":9.0,
          "seasons":[{
            "number":1,
            "imdb_rating":"8.1",
            "rt_rating":"89%",
            "ratings_updated_at":"2026-10-01",
            "episodes":[{"number":1,"name":"Episode","watched":true}]
          }]
        }]}
        """.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "alice");

    TrackedShow imported = showStorage.loadForImport("alice").getFirst();
    assertEquals(2022, imported.firstAirYear);
    assertEquals(1, imported.totalSeasons);
    assertEquals("/poster.jpg", imported.posterPath);
    assertEquals("Saved overview", imported.overview);
    assertEquals(ProductionStatus.ENDED, imported.productionStatus);
    assertEquals("Actor", imported.cast.getFirst().name);
    assertEquals("8.7", imported.imdbRating);
    assertEquals("91%", imported.rtRating);
    assertEquals("2026-10-01", imported.ratingsUpdatedAt);
    assertEquals(9.0, imported.personalRating);
    assertEquals("8.1", imported.seasons.getFirst().imdbRating);
    assertEquals("89%", imported.seasons.getFirst().rtRating);
    assertEquals("2026-10-01", imported.seasons.getFirst().ratingsUpdatedAt);
    assertTrue(imported.seasons.getFirst().episodes.getFirst().watched);
  }

  @Test
  void reimportPreservesExistingShowAndSeasonRatingsForMatchingTmdbId() throws Exception {
    TrackedShow existing = show("Ratings show", WatchStatus.FINISHED);
    existing.tmdbId = 401L;
    existing.imdbRating = "8.4";
    existing.rtRating = "95%";
    existing.ratingsUpdatedAt = "2026-10-01";
    Season existingSeason = new Season(1);
    existingSeason.imdbRating = "8.0";
    existingSeason.rtRating = "90%";
    existingSeason.ratingsUpdatedAt = "2026-10-01";
    existing.seasons = new java.util.ArrayList<>(List.of(existingSeason));
    showStorage.saveAll("alice", List.of(existing));

    DataImportService reimporter = new DataImportService(
        mapper,
        new ImportService(),
        showStorage,
        movieStorage,
        mock(SseController.class));

    reimporter.importData(new ByteArrayInputStream(
        """
            {"shows":[{"title":"Ratings show","tmdb_id":401,"seasons":[
              {"number":1,"episodes":[{"number":1,"name":"Episode","watched":false}]}
            ]}]}
            """
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)), "alice");

    TrackedShow imported = showStorage.loadForImport("alice").getFirst();
    assertEquals("8.4", imported.imdbRating);
    assertEquals("95%", imported.rtRating);
    assertEquals("2026-10-01", imported.ratingsUpdatedAt);
    assertEquals("8.0", imported.seasons.getFirst().imdbRating);
    assertEquals("90%", imported.seasons.getFirst().rtRating);
  }

  @Test
  void emptyMoviesArrayClearsTheMovieListAndWritesBackup() throws Exception {
    movieStorage.save("alice", movie(1L, "Existing"));

    importJson("{\"version\":2,\"movies\":[]}", "alice");

    assertTrue(movieStorage.load("alice").isEmpty());
    assertTrue(Files.exists(tempDir.resolve("movies").resolve("alice.json.bak")));
  }

  @Test
  void moviesOnlyImportLeavesShowsUntouched() throws Exception {
    TrackedShow existingShow = show("Existing show", WatchStatus.FINISHED);
    showStorage.saveAll("alice", List.of(existingShow));
    byte[] oldShowFile = Files.readAllBytes(tempDir.resolve("users").resolve("alice.json"));

    importJson("{\"version\":2,\"movies\":[]}", "alice");

    assertArrayEquals(oldShowFile,
        Files.readAllBytes(tempDir.resolve("users").resolve("alice.json")));
    assertEquals("Existing show", showStorage.loadForImport("alice").getFirst().title);
  }

  @Test
  void replacesOnlyPresentShowSections() throws Exception {
    TrackedShow watching = show("Old watching", WatchStatus.WATCHING_NOW);
    TrackedShow finished = show("Keep finished", WatchStatus.FINISHED);
    showStorage.saveAll("alice", List.of(watching, finished));

    importJson("{\"shows\":[]}", "alice");

    List<TrackedShow> shows = showStorage.loadForImport("alice");
    assertEquals(1, shows.size());
    assertEquals("Keep finished", shows.getFirst().title);
    assertTrue(Files.exists(tempDir.resolve("users").resolve("alice.json.bak")));
  }

  @Test
  void invalidMoviesNullMoviesAndUnknownVersionWriteNothing() throws Exception {
    movieStorage.save("alice", movie(7L, "Keep"));
    showStorage.saveAll("alice", List.of(show("Keep show", WatchStatus.FINISHED)));
    Path movieFile = tempDir.resolve("movies").resolve("alice.json");
    Path showFile = tempDir.resolve("users").resolve("alice.json");
    byte[] original = Files.readAllBytes(movieFile);
    byte[] originalShows = Files.readAllBytes(showFile);

    for (String invalid : List.of(
        "{\"version\":2,\"shows\":[],\"movies\":[{\"tmdbId\":-1}]}",
        "{\"version\":2,\"movies\":null}",
        "{\"version\":3,\"movies\":[]}")) {
      assertThrows(InvalidImportException.class, () -> importJson(invalid, "alice"));
      assertArrayEquals(original, Files.readAllBytes(movieFile));
      assertArrayEquals(originalShows, Files.readAllBytes(showFile));
    }
  }

  @Test
  void duplicateTmdbIdsAreRejectedWithoutWriting() throws Exception {
    movieStorage.save("alice", movie(7L, "Keep"));
    Path movieFile = tempDir.resolve("movies").resolve("alice.json");
    byte[] original = Files.readAllBytes(movieFile);
    String duplicatePayload = """
        {"version":2,"movies":[
          {"tmdbId":99,"title":"First"},
          {"tmdbId":99,"title":"Second"}
        ]}
        """;

    assertThrows(InvalidImportException.class, () -> importJson(duplicatePayload, "alice"));

    assertArrayEquals(original, Files.readAllBytes(movieFile));
  }

  @Test
  void invalidRatingStatusMediaTypeAndWatchedDateAreRejected() {
    List<String> invalidMovies = List.of(
        "{\"tmdbId\":1,\"personalRating\":7.2}",
        "{\"tmdbId\":1,\"status\":\"WATCHING_NOW\"}",
        "{\"tmdbId\":1,\"mediaType\":\"tv\"}",
        "{\"tmdbId\":1,\"status\":\"PLANNED\",\"watchedDate\":\"2026-01-01\"}");
    for (String movie : invalidMovies) {
      String payload = "{\"version\":2,\"movies\":[" + movie + "]}";
      assertThrows(InvalidImportException.class, () -> importJson(payload, "alice"));
    }
  }

  @Test
  void secondWriteFailureRestoresMoviesFromBeforeImportSnapshot() throws Exception {
    MovieEntry existing = movie(5L, "Existing");
    movieStorage.save("alice", existing);
    byte[] originalMovies = Files.readAllBytes(tempDir.resolve("movies").resolve("alice.json"));
    JsonStorageService failingShowStorage = new JsonStorageService(
        tempDir.resolve("failing-users").toString(),
        mapper, mock(SseController.class)) {
      @Override
      public synchronized void replaceForImport(String userId, List<TrackedShow> shows)
          throws IOException {
        throw new IOException("simulated show-file write failure");
      }
    };
    DataImportService failingImporter = new DataImportService(
        mapper, new ImportService(), failingShowStorage, movieStorage, mock(SseController.class));

    assertThrows(IOException.class, () -> failingImporter.importData(
        new ByteArrayInputStream("""
            {"version":2,"shows":[],"movies":[{"tmdbId":55,"title":"Replacement"}]}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "alice"));

    assertArrayEquals(originalMovies,
        Files.readAllBytes(tempDir.resolve("movies").resolve("alice.json")));
    assertNotNull(movieStorage.load("alice").getFirst().id);
    assertEquals("Existing", movieStorage.load("alice").getFirst().title);
    try (var ignored = Files.list(tempDir.resolve("movies"))) {
      assertArrayEquals(originalMovies,
          Files.readAllBytes(tempDir.resolve("movies").resolve("alice.json")));
    }
    try (var files = Files.list(tempDir.resolve("movies"))) {
      assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
    }
  }

  @Test
  void acceptedLegacyPayloadWithoutVersionIsTreatedAsVersionOne() throws Exception {
    importJson("{\"movies\":[]}", "alice");
    assertTrue(movieStorage.load("alice").isEmpty());
  }

  @Test
  void v2ExportRetainsEveryLegacyTopLevelShowKeyAndAddsVersionAndMovies() throws Exception {
    ImportExportPayload payload = new ImportService().toPayload(List.of());
    payload.version = 2;
    payload.movies = List.of();

    var root = mapper.readTree(mapper.writeValueAsBytes(payload));

    java.util.Set<String> keys = new java.util.HashSet<>();
    root.fieldNames().forEachRemaining(keys::add);
    assertEquals(java.util.Set.of(
            "version", "shows", "watchlist_shows", "up_to_date_shows",
            "finished_shows", "stopped_shows", "movies"),
        keys);
    assertEquals(2, root.path("version").asInt());
    assertTrue(root.path("movies").isArray());
  }

  private DataImportService.ImportSummary importJson(String json, String userId)
      throws IOException {
    return importer.importData(new ByteArrayInputStream(
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), userId);
  }

  private static MovieEntry movie(long tmdbId, String title) {
    MovieEntry movie = new MovieEntry();
    movie.tmdbId = tmdbId;
    movie.title = title;
    return movie;
  }

  private static TrackedShow show(String title, WatchStatus status) {
    TrackedShow show = new TrackedShow();
    show.id = UUID.randomUUID().toString();
    show.title = title;
    show.watchStatus = status;
    return show;
  }

  private static TrackedShow find(List<TrackedShow> shows, Long tmdbId) {
    return shows.stream().filter(show -> tmdbId.equals(show.tmdbId)).findFirst().orElseThrow();
  }
}
