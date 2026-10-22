package com.tvtracker.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tvtracker.controller.SseController;
import com.tvtracker.exception.InvalidImportException;
import com.tvtracker.model.ImportExportPayload;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.MovieStatus;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.model.WatchStatus;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DataImportService {
    private static final List<ShowSection> SHOW_SECTIONS = List.of(
            new ShowSection("shows", WatchStatus.WATCHING_NOW),
            new ShowSection("watchlist_shows", WatchStatus.NOT_WATCHED),
            new ShowSection("up_to_date_shows", WatchStatus.UP_TO_DATE),
            new ShowSection("finished_shows", WatchStatus.FINISHED),
            new ShowSection("stopped_shows", WatchStatus.DROPPED));

    private final ObjectMapper mapper;
    private final ImportService showImporter;
    private final JsonStorageService showStorage;
    private final MovieStorageService movieStorage;
    private final SseController sse;

    public DataImportService(
            ObjectMapper mapper,
            ImportService showImporter,
            JsonStorageService showStorage,
            MovieStorageService movieStorage,
            SseController sse) {
        this.mapper = mapper;
        this.showImporter = showImporter;
        this.showStorage = showStorage;
        this.movieStorage = movieStorage;
        this.sse = sse;
    }

    public synchronized ImportSummary importData(InputStream input, String userId) throws IOException {
        JsonNode root;
        try {
            root = mapper.readTree(input);
        } catch (JsonProcessingException e) {
            throw new InvalidImportException("Import file is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidImportException("Import file must contain a JSON object");
        }

        int version = validateVersion(root);
        boolean showsPresent = false;
        for (ShowSection section : SHOW_SECTIONS) {
            if (root.has(section.key())) {
                showsPresent = true;
                JsonNode value = root.get(section.key());
                if (!value.isArray()) {
                    throw new InvalidImportException("Import key '" + section.key() + "' must be an array");
                }
            }
        }
        boolean moviesPresent = root.has("movies");
        List<MovieEntry> movies = moviesPresent ? validateMovies(root.get("movies")) : null;

        ImportExportPayload payload;
        try {
            ObjectNode showPayload = root.deepCopy();
            showPayload.remove("movies");
            payload = mapper.treeToValue(showPayload, ImportExportPayload.class);
        } catch (JsonProcessingException e) {
            throw new InvalidImportException("Import file contains invalid show data", e);
        }
        payload.version = version;

        MovieStorageService.ImportFileSnapshot movieSnapshot =
                moviesPresent ? movieStorage.snapshotForImport(userId) : null;
        List<TrackedShow> importedShows = List.of();
        List<String> failedTitles = List.of();
        List<TrackedShow> showsToWrite = null;
        if (showsPresent) {
            ImportService.ImportResult resolved = showImporter.resolve(payload);
            importedShows = resolved.shows();
            failedTitles = resolved.stubTitles();

            List<TrackedShow> current = showStorage.loadForImport(userId);
            showsToWrite = mergeShowSections(current, importedShows, root);
        }

        boolean movieWriteAttempted = false;
        try {
            if (moviesPresent) {
                movieWriteAttempted = true;
                movieStorage.replaceForImport(userId, movies);
            }
            if (showsPresent) {
                showStorage.replaceForImport(userId, showsToWrite);
            }
        } catch (IOException | RuntimeException e) {
            if (movieWriteAttempted) {
                try {
                    movieStorage.restoreAfterFailedImport(movieSnapshot);
                } catch (IOException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            throw new IOException("Import failed; the previous data was restored where possible", e);
        }

        if (showsPresent) {
            sse.broadcast();
        }
        if (moviesPresent) {
            sse.broadcastMoviesChanged();
        }
        return new ImportSummary(importedShows.size(), moviesPresent ? movies.size() : 0, failedTitles);
    }

    private static int validateVersion(JsonNode root) {
        JsonNode versionNode = root.get("version");
        if (versionNode == null) {
            return 1;
        }
        if (!versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
            throw new InvalidImportException("Import version must be an integer");
        }
        int version = versionNode.asInt();
        if (version < 1 || version > 2) {
            throw new InvalidImportException("Unsupported import version: " + version + " (supported: 1-2)");
        }
        return version;
    }

    private List<MovieEntry> validateMovies(JsonNode moviesNode) {
        if (moviesNode == null || moviesNode.isNull()) {
            throw new InvalidImportException("Import key 'movies' must be an array, not null");
        }
        if (!moviesNode.isArray()) {
            throw new InvalidImportException("Import key 'movies' must be an array");
        }

        List<MovieEntry> movies = new ArrayList<>();
        Set<Long> tmdbIds = new HashSet<>();
        for (int index = 0; index < moviesNode.size(); index++) {
            JsonNode node = moviesNode.get(index);
            String fieldPrefix = "movies[" + index + "]";
            if (!node.isObject()) {
                throw new InvalidImportException(fieldPrefix + " must be an object");
            }
            JsonNode tmdbIdNode = node.get("tmdbId");
            if (tmdbIdNode == null || !tmdbIdNode.isIntegralNumber()
                    || !tmdbIdNode.canConvertToLong() || tmdbIdNode.asLong() <= 0) {
                throw new InvalidImportException(fieldPrefix + ".tmdbId must be a positive integer");
            }
            long tmdbId = tmdbIdNode.asLong();
            if (!tmdbIds.add(tmdbId)) {
                throw new InvalidImportException("Duplicate movie tmdbId: " + tmdbId);
            }

            MovieEntry movie;
            try {
                ObjectNode movieNode = node.deepCopy();
                movieNode.remove("lastRefreshedAt");
                movie = mapper.treeToValue(movieNode, MovieEntry.class);
            } catch (JsonProcessingException e) {
                throw new InvalidImportException(fieldPrefix + " contains invalid movie data", e);
            }
            movie.tmdbId = tmdbId;

            JsonNode statusNode = node.get("status");
            if (statusNode == null || statusNode.isNull()) {
                movie.status = MovieStatus.PLANNED;
            } else if (!statusNode.isTextual()) {
                throw new InvalidImportException(fieldPrefix + ".status must be PLANNED or WATCHED");
            } else {
                try {
                    movie.status = MovieStatus.valueOf(statusNode.asText().trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new InvalidImportException(fieldPrefix + ".status must be PLANNED or WATCHED");
                }
            }

            JsonNode ratingNode = node.get("personalRating");
            if (ratingNode != null && !ratingNode.isNull()) {
                if (!ratingNode.isNumber()) {
                    throw new InvalidImportException(fieldPrefix + ".personalRating must be a number or null");
                }
                double rating = ratingNode.asDouble();
                if (!Double.isFinite(rating) || rating < 0 || rating > 10
                        || Math.abs(rating * 2 - Math.rint(rating * 2)) > 0.000001) {
                    throw new InvalidImportException(fieldPrefix
                            + ".personalRating must be between 0 and 10 in 0.5 steps");
                }
                movie.personalRating = rating;
            } else {
                movie.personalRating = null;
            }

            JsonNode castNode = node.get("cast");
            if (castNode != null && !castNode.isNull() && !castNode.isArray()) {
                throw new InvalidImportException(fieldPrefix + ".cast must be an array or null");
            }
            if (movie.cast == null) {
                movie.cast = new ArrayList<>();
            }

            JsonNode mediaTypeNode = node.get("mediaType");
            if (mediaTypeNode != null) {
                if (!mediaTypeNode.isTextual()
                        || !"movie".equalsIgnoreCase(mediaTypeNode.asText())) {
                    throw new InvalidImportException(fieldPrefix + ".mediaType must be 'movie' or omitted");
                }
            }
            movie.mediaType = com.tvtracker.model.MediaType.MOVIE;

            JsonNode watchedDateNode = node.get("watchedDate");
            if (watchedDateNode != null && !watchedDateNode.isNull()) {
                if (!watchedDateNode.isTextual() || movie.status != MovieStatus.WATCHED) {
                    throw new InvalidImportException(fieldPrefix + ".watchedDate is only allowed for WATCHED movies");
                }
            }

            JsonNode idNode = node.get("id");
            if (idNode != null && !idNode.isNull() && !idNode.isTextual()) {
                throw new InvalidImportException(fieldPrefix + ".id must be a string or null");
            }
            if (idNode == null || idNode.isNull() || movie.id == null || movie.id.isBlank()) {
                movie.id = UUID.randomUUID().toString();
            }
            movie.lastRefreshedAt = null;
            movies.add(movie);
        }
        return movies;
    }

    private static List<TrackedShow> mergeShowSections(
            List<TrackedShow> current, List<TrackedShow> imported, JsonNode root) {
        List<TrackedShow> merged = new ArrayList<>(current);
        for (ShowSection section : SHOW_SECTIONS) {
            if (!root.has(section.key())) {
                continue;
            }
            merged.removeIf(show -> show.watchStatus == section.status());
            imported.stream()
                    .filter(show -> show.watchStatus == section.status())
                    .forEach(merged::add);
        }
        return merged;
    }

    public record ImportSummary(int showsImported, int moviesImported, List<String> failedTitles) {}

    private record ShowSection(String key, WatchStatus status) {}
}
