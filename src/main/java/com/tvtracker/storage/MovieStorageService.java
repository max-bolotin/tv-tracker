package com.tvtracker.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.controller.SseController;
import com.tvtracker.exception.MovieNotFoundException;
import com.tvtracker.model.MovieEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class MovieStorageService {
    private static final TypeReference<List<MovieEntry>> MOVIE_LIST_TYPE = new TypeReference<>() {};

    private final Path moviesRoot;
    private final ObjectMapper mapper;
    private final SseController sse;

    public MovieStorageService(
            @Value("${app.storage.path:./data/users/}") String showStoragePath,
            @Value("${app.storage.movies-path:}") String moviesStoragePath,
            ObjectMapper mapper,
            SseController sse) {
        this.moviesRoot = resolveMoviesRoot(showStoragePath, moviesStoragePath);
        this.mapper = mapper;
        this.sse = sse;
    }

    public synchronized List<MovieEntry> load() throws IOException {
        return load("default");
    }

    public synchronized List<MovieEntry> load(String userId) throws IOException {
        Path file = resolveUserFile(userId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        List<MovieEntry> movies = mapper.readValue(file.toFile(), MOVIE_LIST_TYPE);
        if (movies == null) {
            throw new IOException("Movie storage file must contain a JSON array: " + file);
        }
        return movies;
    }

    public synchronized Optional<MovieEntry> findById(String id) throws IOException {
        return findById("default", id);
    }

    public synchronized Optional<MovieEntry> findById(String userId, String id) throws IOException {
        return load(userId).stream()
                .filter(movie -> Objects.equals(movie.id, id))
                .findFirst();
    }

    public synchronized MovieEntry update(String userId, String id, Consumer<MovieEntry> mutator)
            throws IOException {
        return update(userId, id, mutator, true);
    }

    public synchronized MovieEntry update(String userId, String id, Consumer<MovieEntry> mutator,
                                          boolean broadcastChange) throws IOException {
        Objects.requireNonNull(mutator, "mutator");
        List<MovieEntry> movies = load(userId);
        MovieEntry movie = movies.stream()
                .filter(candidate -> Objects.equals(candidate.id, id))
                .findFirst()
                .orElseThrow(() -> new MovieNotFoundException(id));
        mutator.accept(movie);
        writeMoviesToFile(resolveUserFile(userId), movies);
        if (broadcastChange) {
            sse.broadcastMoviesChanged();
        }
        return movie;
    }

    public synchronized MovieEntry save(MovieEntry movie) throws IOException {
        return save("default", movie);
    }

    public synchronized MovieEntry save(String userId, MovieEntry movie) throws IOException {
        Objects.requireNonNull(movie, "movie");
        if (movie.id == null || movie.id.isBlank()) {
            movie.id = UUID.randomUUID().toString();
        }

        List<MovieEntry> movies = load(userId);
        int existingIndex = -1;
        for (int i = 0; i < movies.size(); i++) {
            if (Objects.equals(movies.get(i).id, movie.id)) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex >= 0) {
            movies.set(existingIndex, movie);
        } else {
            movies.addFirst(movie);
        }

        writeMoviesToFile(resolveUserFile(userId), movies);
        sse.broadcastMoviesChanged();
        return movie;
    }

    public synchronized AddResult saveIfTmdbIdAbsent(String userId, MovieEntry movie) throws IOException {
        Objects.requireNonNull(movie, "movie");
        Objects.requireNonNull(movie.tmdbId, "movie.tmdbId");
        Optional<MovieEntry> existing = load(userId).stream()
                .filter(candidate -> Objects.equals(candidate.tmdbId, movie.tmdbId))
                .findFirst();
        return existing.isPresent()
                ? new AddResult(existing.get(), false)
                : new AddResult(save(userId, movie), true);
    }

    public synchronized AddResult saveIfTmdbIdAbsent(MovieEntry movie) throws IOException {
        return saveIfTmdbIdAbsent("default", movie);
    }

    public record AddResult(MovieEntry movie, boolean created) {}

    public synchronized boolean delete(String id) throws IOException {
        return delete("default", id);
    }

    public synchronized boolean delete(String userId, String id) throws IOException {
        List<MovieEntry> movies = load(userId);
        boolean removed = movies.removeIf(movie -> Objects.equals(movie.id, id));
        if (removed) {
            writeMoviesToFile(resolveUserFile(userId), movies);
            sse.broadcastMoviesChanged();
        }
        return removed;
    }

    public synchronized void reorder(List<String> orderedIds) throws IOException {
        reorder("default", orderedIds);
    }

    public synchronized void reorder(String userId, List<String> orderedIds) throws IOException {
        Objects.requireNonNull(orderedIds, "orderedIds");
        List<MovieEntry> movies = load(userId);
        Map<String, MovieEntry> byId = new LinkedHashMap<>();
        movies.forEach(movie -> byId.put(movie.id, movie));

        List<MovieEntry> reordered = new ArrayList<>(movies.size());
        for (String id : orderedIds) {
            MovieEntry movie = byId.remove(id);
            if (movie != null) {
                reordered.add(movie);
            }
        }
        reordered.addAll(byId.values());

        writeMoviesToFile(resolveUserFile(userId), reordered);
        sse.broadcastMoviesChanged();
    }

    static Path resolveMoviesRoot(String showStoragePath, String moviesStoragePath) {
        if (moviesStoragePath != null && !moviesStoragePath.isBlank()) {
            return Path.of(moviesStoragePath).toAbsolutePath().normalize();
        }

        Path configuredShowStorage = Path.of(
                showStoragePath == null || showStoragePath.isBlank() ? "./data/users/" : showStoragePath)
                .toAbsolutePath()
                .normalize();
        Path dataRoot = configuredShowStorage.getParent();
        if (dataRoot == null) {
            dataRoot = configuredShowStorage;
        }
        return dataRoot.resolve("movies");
    }

    private Path resolveUserFile(String userId) {
        String normalizedUserId = userId == null || userId.isBlank()
                ? "default"
                : userId.replaceAll("[^A-Za-z0-9._-]", "_");
        return moviesRoot.resolve(normalizedUserId + ".json");
    }

    private void writeMoviesToFile(Path file, List<MovieEntry> movies) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path tempFile = parent.resolve(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.createFile(tempFile);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), movies);
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

}
