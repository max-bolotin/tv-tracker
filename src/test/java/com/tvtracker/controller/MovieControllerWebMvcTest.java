package com.tvtracker.controller;

import com.tvtracker.config.SecurityConfig;
import com.tvtracker.exception.GlobalExceptionHandler;
import com.tvtracker.exception.MovieNotFoundException;
import com.tvtracker.exception.UpstreamServiceException;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.storage.MovieStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MovieController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "GOOGLE_CLIENT_ID=test-client",
        "GOOGLE_CLIENT_SECRET=test-secret",
        "app.frontend.url=http://localhost:5173"
})
class MovieControllerWebMvcTest {
    private static final String USER_ID = "web-test-user";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieStorageService storage;

    @MockitoBean
    private MetadataService metadata;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void blankSearchReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/movies/search").param("q", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void invalidStatusAndRatingReturnBadRequest() throws Exception {
        mockMvc.perform(patch("/api/movies/movie-1/status")
                        .with(user(USER_ID))
                        .contentType("application/json")
                        .content("""
                                {"status":"WATCHING_NOW"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/movies/movie-1/rating")
                        .with(user(USER_ID))
                        .contentType("application/json")
                        .content("""
                                {"personalRating":7.2}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownMovieReturnsNotFound() throws Exception {
        when(storage.findById(USER_ID, "missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/movies/missing").with(user(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Movie not found: missing"));
    }

    @Test
    void tmdbMovieNotFoundReturns404AndUpstreamOutageReturns502() throws Exception {
        when(storage.load(USER_ID)).thenReturn(List.of());
        when(metadata.fetchMovieDetails(404L)).thenThrow(new MovieNotFoundException("TMDB movie not found: 404"));
        when(metadata.searchMovies("outage")).thenThrow(new UpstreamServiceException("TMDB movie search failed"));
        when(metadata.fetchMovieDetails(503L)).thenThrow(new UpstreamServiceException("OMDb movie enrichment failed"));

        mockMvc.perform(post("/api/movies")
                        .with(user(USER_ID))
                        .contentType("application/json")
                        .content("""
                                {"tmdbId":404}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/movies/search").param("q", "outage"))
                .andExpect(status().isBadGateway());

        mockMvc.perform(post("/api/movies")
                        .with(user(USER_ID))
                        .contentType("application/json")
                        .content("""
                                {"tmdbId":503}
                                """))
                .andExpect(status().isBadGateway());
    }

    @Test
    void addReturns201WithMovieAndCreatedShapeForNewMovie() throws Exception {
        MovieEntry movie = movie("Arrival", 42L);
        when(storage.load(USER_ID)).thenReturn(List.of());
        when(metadata.fetchMovieDetails(42L)).thenReturn(movie);
        when(storage.saveIfTmdbIdAbsent(eq(USER_ID), any(MovieEntry.class)))
                .thenReturn(new MovieStorageService.AddResult(movie, true));

        mockMvc.perform(post("/api/movies")
                        .with(user(USER_ID))
                        .contentType("application/json")
                        .content("""
                                {"tmdbId":42}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.movie.tmdbId").value(42))
                .andExpect(jsonPath("$.movie.title").value("Arrival"));
    }

    @Test
    void addReturns200WithMovieAndCreatedShapeForExistingMovie() throws Exception {
        MovieEntry movie = movie("Arrival", 42L);
        when(storage.load(USER_ID)).thenReturn(List.of(movie));

        mockMvc.perform(post("/api/movies")
                        .with(user(USER_ID))
                        .contentType("application/json")
                        .content("""
                                {"tmdbId":42}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.movie.tmdbId").value(42))
                .andExpect(jsonPath("$.movie.title").value("Arrival"));
    }

    @Test
    void unauthenticatedMovieListReturns401() throws Exception {
        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isUnauthorized());
    }

    private static MovieEntry movie(String title, long tmdbId) {
        MovieEntry movie = new MovieEntry();
        movie.id = "movie-1";
        movie.title = title;
        movie.tmdbId = tmdbId;
        return movie;
    }
}
