package com.tvtracker.controller;

import com.tvtracker.exception.InvalidMovieRequestException;
import com.tvtracker.exception.MovieNotFoundException;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.MovieStatus;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.security.CurrentUserContext;
import com.tvtracker.storage.MovieStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MovieControllerTest {
    private static final String USER_ID = "user-123";

    private MovieStorageService storage;
    private MetadataService metadata;
    private MovieController controller;
    private MockedStatic<CurrentUserContext> userContext;

    @BeforeEach
    void setUp() {
        storage = mock(MovieStorageService.class);
        metadata = mock(MetadataService.class);
        controller = new MovieController(storage, metadata);
        userContext = mockStatic(CurrentUserContext.class);
        userContext.when(CurrentUserContext::currentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        userContext.close();
    }

    @Test
    void addFetchesServerMetadataUsingOnlyTmdbIdAndReturnsCreatedResult() throws Exception {
        MovieEntry fetched = movie("Arrival", 42L);
        MovieStorageService.AddResult addResult = new MovieStorageService.AddResult(fetched, true);
        when(storage.load(USER_ID)).thenReturn(List.of());
        when(metadata.fetchMovieDetails(42L)).thenReturn(fetched);
        when(storage.saveIfTmdbIdAbsent(eq(USER_ID), any(MovieEntry.class))).thenReturn(addResult);

        var response = controller.add(new MovieController.AddMovieRequest(42L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(addResult);
        verify(metadata).fetchMovieDetails(42L);
        verify(storage).saveIfTmdbIdAbsent(USER_ID, fetched);
    }

    @Test
    void addingAlreadyTrackedTmdbIdReturnsExistingMovieWithoutFetchingMetadata() throws Exception {
        MovieEntry existing = movie("Arrival", 42L);
        when(storage.load(USER_ID)).thenReturn(List.of(existing));

        var response = controller.add(new MovieController.AddMovieRequest(42L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new MovieStorageService.AddResult(existing, false));
        verify(metadata, org.mockito.Mockito.never()).fetchMovieDetails(42L);
    }

    @Test
    void addRequiresPositiveTmdbId() {
        assertThatThrownBy(() -> controller.add(new MovieController.AddMovieRequest(0L)))
                .isInstanceOf(InvalidMovieRequestException.class);
    }

    @Test
    void watchedStatusSetsDateAndUnmarkingClearsIt() throws Exception {
        MovieEntry movie = movie("Arrival", 42L);
        when(storage.findById(USER_ID, "movie-id")).thenReturn(Optional.of(movie));
        when(storage.save(USER_ID, movie)).thenReturn(movie);

        MovieEntry watched = controller.updateStatus("movie-id", new MovieController.StatusUpdate("WATCHED"));
        assertThat(watched.status).isEqualTo(MovieStatus.WATCHED);
        assertThat(watched.watchedDate).isNotBlank();

        MovieEntry planned = controller.updateStatus("movie-id", new MovieController.StatusUpdate("PLANNED"));
        assertThat(planned.status).isEqualTo(MovieStatus.PLANNED);
        assertThat(planned.watchedDate).isNull();
    }

    @Test
    void invalidStatusAndPersonalRatingAreRejected() throws Exception {
        MovieEntry movie = movie("Arrival", 42L);
        when(storage.findById(USER_ID, "movie-id")).thenReturn(Optional.of(movie));

        assertThatThrownBy(() -> controller.updateStatus(
                "movie-id", new MovieController.StatusUpdate("WATCHING_NOW")))
                .isInstanceOf(InvalidMovieRequestException.class);
        assertThatThrownBy(() -> controller.updatePersonalRating(
                "movie-id", new MovieController.PersonalRatingUpdate(7.2)))
                .isInstanceOf(InvalidMovieRequestException.class);
        assertThatThrownBy(() -> controller.updatePersonalRating(
                "movie-id", new MovieController.PersonalRatingUpdate(10.5)))
                .isInstanceOf(InvalidMovieRequestException.class);
    }

    @Test
    void personalRatingAllowsHalfStepsAndNullToClear() throws Exception {
        MovieEntry movie = movie("Arrival", 42L);
        when(storage.findById(USER_ID, "movie-id")).thenReturn(Optional.of(movie));
        when(storage.save(USER_ID, movie)).thenReturn(movie);

        controller.updatePersonalRating("movie-id", new MovieController.PersonalRatingUpdate(8.5));
        assertThat(movie.personalRating).isEqualTo(8.5);

        controller.updatePersonalRating("movie-id", new MovieController.PersonalRatingUpdate(null));
        assertThat(movie.personalRating).isNull();
    }

    @Test
    void refreshPreservesPersonalStateWhileReplacingMetadata() throws Exception {
        MovieEntry existing = movie("Old title", 42L);
        existing.id = "movie-id";
        existing.status = MovieStatus.WATCHED;
        existing.watchedDate = "2026-10-20";
        existing.personalRating = 9.0;
        MovieEntry refreshed = movie("Updated title", 42L);
        when(storage.findById(USER_ID, "movie-id")).thenReturn(Optional.of(existing));
        when(metadata.fetchMovieDetails(42L)).thenReturn(refreshed);
        when(storage.save(eq(USER_ID), any(MovieEntry.class))).thenAnswer(invocation -> invocation.getArgument(1));

        MovieEntry result = controller.refresh("movie-id");

        assertThat(result.title).isEqualTo("Updated title");
        assertThat(result.id).isEqualTo("movie-id");
        assertThat(result.status).isEqualTo(MovieStatus.WATCHED);
        assertThat(result.watchedDate).isEqualTo("2026-10-20");
        assertThat(result.personalRating).isEqualTo(9.0);
    }

    @Test
    void getMissingMovieThrowsMovieNotFound() throws IOException {
        when(storage.findById(USER_ID, "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get("missing"))
                .isInstanceOf(MovieNotFoundException.class);
    }

    private static MovieEntry movie(String title, long tmdbId) {
        MovieEntry movie = new MovieEntry();
        movie.id = "movie-id";
        movie.title = title;
        movie.tmdbId = tmdbId;
        return movie;
    }
}
