package com.tvtracker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tvtracker.exception.UpstreamServiceException;
import com.tvtracker.model.MovieEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OmdbProviderMovieTest {
    private HttpServer server;
    private String responseBody;
    private int responseStatus = 200;
    private final AtomicReference<String> requestTarget = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestTarget.set(exchange.getRequestURI().toString());
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
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
    void enrichMovieUsesExistingOmdbClientAndMapsImdbAndRottenTomatoesRatings() {
        responseBody = """
                {
                  "Response":"True",
                  "imdbRating":"N/A",
                  "Ratings":[{"Source":"Rotten Tomatoes","Value":"94%"}]
                }
                """;
        MovieEntry movie = new MovieEntry();
        movie.imdbId = "tt0000042";

        provider().enrichMovie(movie);

        assertEquals("test-key", queryParameter("apikey"));
        assertEquals("tt0000042", queryParameter("i"));
        assertEquals("movie", queryParameter("type"));
        assertNull(movie.imdbRating);
        assertEquals("94%", movie.rottenTomatoesScore);
    }

    @Test
    void missingOmdbRatingsAreNullAndDoNotBreakMovieEnrichment() {
        responseBody = """
                {"Response":"True","imdbRating":"8.1","Ratings":[]}
                """;
        MovieEntry movie = new MovieEntry();
        movie.imdbId = "tt0000042";

        provider().enrichMovie(movie);

        assertEquals("8.1", movie.imdbRating);
        assertNull(movie.rottenTomatoesScore);
    }

    @Test
    void omdbErrorResponseLeavesExistingMovieRatingsUntouched() {
        responseBody = """
                {"Response":"False","Error":"Movie not found!"}
                """;
        MovieEntry movie = new MovieEntry();
        movie.imdbId = "tt0000042";
        movie.imdbRating = "7.0";
        movie.rottenTomatoesScore = "80%";

        provider().enrichMovie(movie);

        assertEquals("7.0", movie.imdbRating);
        assertEquals("80%", movie.rottenTomatoesScore);
    }

    @Test
    void omdbHttpFailureIsReportedAsUpstreamFailure() {
        responseBody = """
                {"Response":"False","Error":"Service unavailable"}
                """;
        responseStatus = 503;
        MovieEntry movie = new MovieEntry();
        movie.imdbId = "tt0000042";

        org.junit.jupiter.api.Assertions.assertThrows(
                UpstreamServiceException.class, () -> provider().enrichMovie(movie));
    }

    private OmdbProvider provider() {
        return new OmdbProvider(
                "test-key",
                "http://localhost:" + server.getAddress().getPort(),
                new ObjectMapper());
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
