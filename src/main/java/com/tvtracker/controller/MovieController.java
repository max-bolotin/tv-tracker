package com.tvtracker.controller;

import com.tvtracker.exception.InvalidMovieRequestException;
import com.tvtracker.exception.MovieNotFoundException;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.MovieSearchResult;
import com.tvtracker.model.MovieStatus;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.provider.MovieMetadataMerger;
import com.tvtracker.security.CurrentUserContext;
import com.tvtracker.storage.MovieStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/movies")
public class MovieController {
    private final MovieStorageService storage;
    private final MetadataService metadata;
    private final Clock clock;

    public MovieController(MovieStorageService storage, MetadataService metadata, Clock clock) {
        this.storage = storage;
        this.metadata = metadata;
        this.clock = clock;
    }

    @GetMapping("/search")
    public List<MovieSearchResult> search(@RequestParam String q) {
        if (q.isBlank()) {
            throw new InvalidMovieRequestException("Search query must not be blank");
        }
        if (q.length() > 100) {
            throw new InvalidMovieRequestException("Search query must not exceed 100 characters");
        }
        return metadata.searchMovies(q);
    }

    @GetMapping("/details")
    public MovieEntry details(@RequestParam long tmdbId) {
        if (tmdbId <= 0) {
            throw new InvalidMovieRequestException("A positive tmdbId is required");
        }
        return metadata.fetchMovieDetails(tmdbId, false).movie();
    }

    @PostMapping
    public ResponseEntity<MovieStorageService.AddResult> add(@RequestBody AddMovieRequest request)
            throws IOException {
        if (request == null || request.tmdbId() == null || request.tmdbId() <= 0) {
            throw new InvalidMovieRequestException("A positive tmdbId is required");
        }

        String userId = CurrentUserContext.currentUserId();
        MovieStorageService.AddResult result = storage.load(userId).stream()
                .filter(movie -> Objects.equals(movie.tmdbId, request.tmdbId()))
                .findFirst()
                .map(movie -> new MovieStorageService.AddResult(movie, false))
                .orElse(null);
        if (result != null) {
            return ResponseEntity.ok(result);
        }

        MetadataService.MovieMetadataResult resultWithMetadata = metadata.fetchMovieDetails(request.tmdbId());
        MovieEntry fetched = resultWithMetadata.movie();
        fetched.id = null;
        fetched.tmdbId = request.tmdbId();
        fetched.status = MovieStatus.PLANNED;
        fetched.watchedDate = null;
        if (resultWithMetadata.ratingsRefreshed()) {
            fetched.lastRefreshedAt = clock.instant();
        }
        result = storage.saveIfTmdbIdAbsent(userId, fetched);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @GetMapping
    public List<MovieEntry> list(@RequestParam(required = false) MovieStatus status) throws IOException {
        List<MovieEntry> movies = storage.load(CurrentUserContext.currentUserId());
        return status == null
                ? movies
                : movies.stream().filter(movie -> movie.status == status).toList();
    }

    @GetMapping("/{id}")
    public MovieEntry get(@PathVariable String id) throws IOException {
        return findMovie(id);
    }

    @PatchMapping("/{id}/status")
    public MovieEntry updateStatus(@PathVariable String id, @RequestBody StatusUpdate request)
            throws IOException {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new InvalidMovieRequestException("A movie status is required");
        }
        MovieStatus status;
        try {
            status = MovieStatus.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidMovieRequestException("Status must be PLANNED or WATCHED");
        }

        String userId = CurrentUserContext.currentUserId();
        return storage.update(userId, id, movie -> {
            if (status == MovieStatus.WATCHED) {
                if (movie.status != MovieStatus.WATCHED) {
                    movie.watchedDate = LocalDate.now(clock).toString();
                }
            } else {
                movie.watchedDate = null;
            }
            movie.status = status;
        });
    }

    @PatchMapping("/{id}/rating")
    public MovieEntry updatePersonalRating(@PathVariable String id, @RequestBody PersonalRatingUpdate request)
            throws IOException {
        Double rating = request == null ? null : request.personalRating();
        if (rating != null && (!Double.isFinite(rating) || rating < 0 || rating > 10
                || Math.abs(rating * 2 - Math.rint(rating * 2)) > 0.000001)) {
            throw new InvalidMovieRequestException("Personal rating must be between 0 and 10 in 0.5 steps");
        }
        String userId = CurrentUserContext.currentUserId();
        return storage.update(userId, id, movie -> movie.personalRating = rating);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        if (!storage.delete(CurrentUserContext.currentUserId(), id)) {
            throw new MovieNotFoundException(id);
        }
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody List<String> orderedIds) throws IOException {
        if (orderedIds == null) {
            throw new InvalidMovieRequestException("An ordered movie ID list is required");
        }
        storage.reorder(CurrentUserContext.currentUserId(), orderedIds);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/refresh")
    public MovieEntry refresh(@PathVariable String id) throws IOException {
        String userId = CurrentUserContext.currentUserId();
        MovieEntry existing = findMovie(userId, id);
        MetadataService.MovieMetadataResult resultWithMetadata = metadata.fetchMovieDetails(existing.tmdbId);
        MovieEntry fresh = resultWithMetadata.movie();
        return storage.update(userId, id, current -> {
            MovieMetadataMerger.merge(current, fresh);
            if (resultWithMetadata.ratingsRefreshed()) {
                current.lastRefreshedAt = clock.instant();
            }
        });
    }

    private MovieEntry findMovie(String id) throws IOException {
        return findMovie(CurrentUserContext.currentUserId(), id);
    }

    private MovieEntry findMovie(String userId, String id) throws IOException {
        return storage.findById(userId, id).orElseThrow(() -> new MovieNotFoundException(id));
    }

    public record AddMovieRequest(Long tmdbId) {}
    public record StatusUpdate(String status) {}
    public record PersonalRatingUpdate(Double personalRating) {}
}
