package com.tvtracker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.MovieSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TmdbProviderMovieTest {
    private HttpServer server;
    private String responseBody;
    private final AtomicReference<String> requestTarget = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestTarget.set(exchange.getRequestURI().toString());
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void searchMoviesUsesOfficialSearchEndpointAndMapsMovieFields() {
        responseBody = """
                {"results":[{"id":42,"title":"Example Film","release_date":"2020-01-02",
                 "poster_path":"/poster.jpg","overview":"A synopsis"}]}
                """;

        MovieSearchResult result = provider().searchMovies("Example Film").getFirst();

        assertEquals("/3/search/movie", serverPath());
        assertEquals("test-key", queryParameter("api_key"));
        assertEquals("Example Film", queryParameter("query"));
        assertEquals("false", queryParameter("include_adult"));
        assertEquals(42L, result.tmdbId);
        assertEquals("Example Film", result.title);
        assertEquals("2020-01-02", result.releaseDate);
        assertEquals("https://images.example/poster.jpg", result.poster);
        assertEquals("A synopsis", result.overview);
    }

    @Test
    void fetchMovieDetailsUsesSingleDetailsCallWithCombinedAppendAndMapsMovieFields() {
        responseBody = """
                {
                  "id":42,
                  "title":"Example Film",
                  "release_date":"2020-01-02",
                  "runtime":118,
                  "poster_path":"/poster.jpg",
                  "external_ids":{"imdb_id":"tt0000042"},
                  "credits":{"cast":[
                    {"id":1,"name":"Actor One","profile_path":"/actor.jpg"},
                    {"id":2,"name":"Actor Two"},
                    {"id":3,"name":"Actor Three"},
                    {"id":4,"name":"Actor Four"},
                    {"id":5,"name":"Actor Five"},
                    {"id":6,"name":"Actor Six"}
                  ]}
                }
                """;

        MovieEntry movie = provider().fetchMovieDetails(42);

        assertEquals("/3/movie/42", serverPath());
        assertEquals("credits,external_ids", queryParameter("append_to_response"));
        assertEquals(42L, movie.tmdbId);
        assertEquals("Example Film", movie.title);
        assertEquals("2020-01-02", movie.releaseDate);
        assertEquals(118, movie.runtime);
        assertEquals("https://images.example/poster.jpg", movie.poster);
        assertEquals("tt0000042", movie.imdbId);
        assertEquals(5, movie.cast.size());
        assertEquals("https://images.example/actor.jpg", movie.cast.getFirst().profilePath);
        assertEquals("https://www.themoviedb.org/person/1", movie.cast.getFirst().linkUrl);
        assertNull(movie.imdbRating);
        assertNull(movie.rottenTomatoesScore);
    }

    @Test
    void fetchMovieDetailsRejectsErrorPayloadInsteadOfCreatingEmptyMovie() {
        responseBody = """
                {"status_code":34,"status_message":"The resource you requested could not be found."}
                """;

        assertThrows(RuntimeException.class, () -> provider().fetchMovieDetails(42));
    }

    private TmdbProvider provider() {
        return new TmdbProvider(
                "test-key",
                "http://localhost:" + server.getAddress().getPort() + "/3",
                "https://images.example",
                new ObjectMapper());
    }

    private String serverPath() {
        return java.net.URI.create("http://localhost" + requestTarget.get()).getPath();
    }

    private String queryParameter(String key) {
        String rawQuery = java.net.URI.create("http://localhost" + requestTarget.get()).getRawQuery();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
