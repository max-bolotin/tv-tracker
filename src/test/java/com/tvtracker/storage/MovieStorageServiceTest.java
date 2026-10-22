package com.tvtracker.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.controller.SseController;
import com.tvtracker.model.MovieEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MovieStorageServiceTest {
    @TempDir
    Path tempDir;

    private MovieStorageService createService(Path showStoragePath) {
        return createService(showStoragePath, "");
    }

    private MovieStorageService createService(Path showStoragePath, String moviesStoragePath) {
        return new MovieStorageService(
                showStoragePath.toString(),
                moviesStoragePath,
                new ObjectMapper(),
                mock(SseController.class));
    }

    @Test
    void missingMovieFileLoadsAsEmptyWithoutCreatingFiles() throws Exception {
        Path showRoot = tempDir.resolve("users");
        MovieStorageService storage = createService(showRoot);

        assertTrue(storage.load("new-user").isEmpty());
        assertFalse(Files.exists(tempDir.resolve("movies")));
    }

    @Test
    void savesAndLoadsMovieWithGeneratedUuid() throws Exception {
        SseController sse = mock(SseController.class);
        MovieStorageService storage = new MovieStorageService(
                tempDir.resolve("users").toString(), "", new ObjectMapper(), sse);
        MovieEntry movie = movie("Arrival");

        MovieEntry saved = storage.save("alice", movie);

        assertNotNull(saved.id);
        assertEquals(saved.id, UUID.fromString(saved.id).toString());
        MovieEntry loaded = storage.load("alice").getFirst();
        assertEquals(saved.id, loaded.id);
        assertEquals(saved.title, loaded.title);
        MovieEntry found = storage.findById("alice", saved.id).orElseThrow();
        assertEquals(saved.id, found.id);
        assertEquals(saved.title, found.title);
        verify(sse).broadcastMoviesChanged();
    }

    @Test
    void savingExistingIdReplacesWithoutChangingItsPosition() throws Exception {
        MovieStorageService storage = createService(tempDir.resolve("users"));
        MovieEntry first = movie("First");
        first.id = "first";
        MovieEntry second = movie("Second");
        second.id = "second";
        storage.save("alice", first);
        storage.save("alice", second);

        MovieEntry replacement = movie("Updated first");
        replacement.id = "first";
        storage.save("alice", replacement);

        assertEquals(List.of("second", "first"),
                storage.load("alice").stream().map(movie -> movie.id).toList());
        assertEquals("Updated first", storage.findById("alice", "first").orElseThrow().title);
    }

    @Test
    void saveIfTmdbIdAbsentReturnsExistingMovieInsteadOfCreatingDuplicate() throws Exception {
        MovieStorageService storage = createService(tempDir.resolve("users"));
        MovieEntry original = movie("Arrival");
        original.tmdbId = 123L;

        MovieStorageService.AddResult inserted = storage.saveIfTmdbIdAbsent("alice", original);
        MovieEntry duplicate = movie("Different local state");
        duplicate.tmdbId = 123L;

        MovieStorageService.AddResult result = storage.saveIfTmdbIdAbsent("alice", duplicate);

        assertTrue(inserted.created());
        assertFalse(result.created());
        assertEquals(inserted.movie().id, result.movie().id);
        assertEquals("Arrival", result.movie().title);
        assertEquals(1, storage.load("alice").size());
    }

    @Test
    void deleteRemovesOnlyMatchingMovie() throws Exception {
        MovieStorageService storage = createService(tempDir.resolve("users"));
        MovieEntry movie = movie("Arrival");
        movie.id = "arrival";
        storage.save("alice", movie);

        assertTrue(storage.delete("alice", "arrival"));
        assertFalse(storage.delete("alice", "arrival"));
        assertTrue(storage.load("alice").isEmpty());
    }

    @Test
    void reorderMovesKnownIdsAndKeepsUnmentionedMovies() throws Exception {
        MovieStorageService storage = createService(tempDir.resolve("users"));
        List<MovieEntry> movies = new ArrayList<>();
        for (String id : List.of("a", "b", "c")) {
            MovieEntry movie = movie(id);
            movie.id = id;
            movies.add(movie);
        }
        for (MovieEntry movie : movies) {
            storage.save("alice", movie);
        }

        storage.reorder("alice", List.of("a", "unknown", "c", "a"));

        assertEquals(List.of("a", "c", "b"),
                storage.load("alice").stream().map(movie -> movie.id).toList());
    }

    @Test
    void concurrentSavesDoNotLoseUpdates() throws Exception {
        MovieStorageService storage = createService(tempDir.resolve("users"));
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<Void>> saves = new ArrayList<>();
            IntStream.range(0, 40).forEach(index -> saves.add(executor.submit(() -> {
                MovieEntry movie = movie("Movie " + index);
                movie.id = "movie-" + index;
                storage.save("alice", movie);
                return null;
            })));
            for (Future<?> save : saves) {
                save.get();
            }
        }

        assertEquals(40, storage.load("alice").size());
    }

    @Test
    void legacySingleFileModeStoresMoviesInSiblingDirectory() throws Exception {
        Path legacyShowsFile = tempDir.resolve("legacy-shows.json");
        String legacyContent = "[]";
        Files.writeString(legacyShowsFile, legacyContent);
        MovieStorageService storage = createService(legacyShowsFile);
        MovieEntry movie = movie("Legacy mode movie");

        storage.save("default", movie);

        assertTrue(Files.exists(tempDir.resolve("movies").resolve("default.json")));
        assertEquals(legacyContent, Files.readString(legacyShowsFile));
        assertEquals(1, storage.load("default").size());
    }

    @Test
    void readingDoesNotModifyMovieFile() throws Exception {
        Path moviesRoot = tempDir.resolve("movies-data");
        Files.createDirectories(moviesRoot);
        String originalJson = "[\n  { \"id\" : \"stable\", \"title\" : \"Unchanged\" }\n]\n";
        Path movieFile = moviesRoot.resolve("alice.json");
        Files.writeString(movieFile, originalJson);
        MovieStorageService storage = createService(
                tempDir.resolve("users"),
                moviesRoot.toString());

        storage.load("alice");
        storage.findById("alice", "stable");

        assertEquals(originalJson, Files.readString(movieFile));
    }

    @Test
    void corruptMovieFileIsNotOverwritten() throws Exception {
        Path moviesRoot = tempDir.resolve("movies-data");
        Files.createDirectories(moviesRoot);
        String corruptJson = "[{\"id\":";
        Path movieFile = moviesRoot.resolve("alice.json");
        Files.writeString(movieFile, corruptJson);
        MovieStorageService storage = createService(tempDir.resolve("users"), moviesRoot.toString());

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> storage.load("alice"));

        assertEquals(corruptJson, Files.readString(movieFile));
    }

    @Test
    void userIdTraversalCharactersAreSanitizedLikeShowStorage() throws Exception {
        Path showRoot = tempDir.resolve("users");
        Path moviesRoot = tempDir.resolve("movies");
        MovieStorageService storage = createService(showRoot);
        MovieEntry movie = movie("Safe path");
        movie.tmdbId = 45L;

        storage.save("../../escape", movie);

        assertTrue(Files.exists(moviesRoot.resolve(".._.._escape.json")));
        assertFalse(Files.exists(moviesRoot.resolve("../../escape.json").normalize()));
        assertTrue(storage.load("../../escape").stream().anyMatch(entry -> entry.id.equals(movie.id)));
    }

    @Test
    void writesLeaveNoTemporaryFilesBehind() throws Exception {
        MovieStorageService storage = createService(tempDir.resolve("users"));
        MovieEntry movie = movie("Temp file check");
        movie.tmdbId = 46L;

        storage.save("alice", movie);
        storage.delete("alice", movie.id);

        try (var files = Files.list(tempDir.resolve("movies"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void movieFilesUseTheSameDefaultPermissionsAsShowFilesWhenSupported() throws Exception {
        Path showRoot = tempDir.resolve("users");
        new JsonStorageService(showRoot.toString(), new ObjectMapper(), mock(SseController.class));
        MovieStorageService storage = createService(showRoot);
        MovieEntry movie = movie("Permission parity");

        storage.save("alice", movie);

        Path showFile = showRoot.resolve("default.json");
        Path movieFile = tempDir.resolve("movies").resolve("alice.json");
        Assumptions.assumeTrue(Files.getFileStore(showFile).supportsFileAttributeView("posix"));
        assertEquals(Files.getPosixFilePermissions(showFile), Files.getPosixFilePermissions(movieFile));
    }

    @Test
    void defaultMoviePathIsSiblingOfShowRootOnMountedDataVolume() {
        assertEquals(Path.of("/app/data/movies").toAbsolutePath().normalize(),
                MovieStorageService.resolveMoviesRoot("/app/data/users/", ""));
    }

    @Test
    void configuredDockerMoviePathIsInsideMountedDataVolume() {
        assertEquals(Path.of("/app/data/movies").toAbsolutePath().normalize(),
                MovieStorageService.resolveMoviesRoot("/app/data/users/", "/app/data/movies/"));
    }

    @Test
    void explicitMoviesPathOverridesDerivedSiblingPath() {
        Path configuredMoviesPath = tempDir.resolve("custom-movies");

        assertEquals(configuredMoviesPath.toAbsolutePath().normalize(),
                MovieStorageService.resolveMoviesRoot(
                        tempDir.resolve("users").toString(),
                        configuredMoviesPath.toString()));
    }

    private static MovieEntry movie(String title) {
        MovieEntry movie = new MovieEntry();
        movie.title = title;
        return movie;
    }
}
