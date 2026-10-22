package com.tvtracker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tvtracker.exception.UpstreamServiceException;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.TrackedShow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmdbProviderMovieTest {
    private HttpServer server;
    private String responseBody;
    private int responseStatus = 200;
    private final AtomicReference<String> requestTarget = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
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

    @Test
    void quotaLimitStartsBackoffAndSuppressesAllOmdbRequestsUntilItExpires() {
        responseStatus = 401;
        responseBody = """
                {"Response":"False","Error":"Request limit reached!"}
                """;
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-22T10:00:00Z"));
        OmdbProvider provider = provider(Clock.fixed(now.get(), ZoneId.of("UTC")));
        MovieEntry movie = movie();

        UpstreamServiceException quotaFailure = org.junit.jupiter.api.Assertions.assertThrows(
                UpstreamServiceException.class, () -> provider.enrichMovie(movie));
        assertTrue(quotaFailure instanceof OmdbProvider.MovieEnrichmentException);
        assertTrue(((OmdbProvider.MovieEnrichmentException) quotaFailure).requestAttempted());
        assertTrue(provider.isBackoffActive());
        assertEquals(1, requestCount.get());

        UpstreamServiceException suppressedFailure = org.junit.jupiter.api.Assertions.assertThrows(
                UpstreamServiceException.class, () -> provider.enrichMovie(movie));
        assertFalse(((OmdbProvider.MovieEnrichmentException) suppressedFailure).requestAttempted());
        assertEquals(1, requestCount.get());

        TrackedShow show = new TrackedShow();
        show.imdbId = "tt0000042";
        provider.enrichShow(show);
        assertEquals(1, requestCount.get());
    }

    @Test
    void requestsResumeAfterConfiguredBackoffPeriod() {
        responseStatus = 401;
        responseBody = """
                {"Response":"False","Error":"Request limit reached!"}
                """;
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-22T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        OmdbProvider provider = provider(clock);
        org.junit.jupiter.api.Assertions.assertThrows(UpstreamServiceException.class,
                () -> provider.enrichMovie(movie()));
        assertEquals(1, requestCount.get());

        now.set(now.get().plus(Duration.ofHours(6)));
        responseStatus = 200;
        responseBody = """
                {"Response":"True","imdbRating":"8.2","Ratings":[]}
                """;
        provider.enrichMovie(movie());

        assertFalse(provider.isBackoffActive());
        assertEquals(2, requestCount.get());
    }

    @Test
    void invalidApiKeyDoesNotStartQuotaBackoff() {
        responseStatus = 401;
        responseBody = """
                {"Response":"False","Error":"Invalid API key!"}
                """;
        OmdbProvider provider = provider(Clock.fixed(
                Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("UTC")));

        org.junit.jupiter.api.Assertions.assertThrows(UpstreamServiceException.class,
                () -> provider.enrichMovie(movie()));
        assertFalse(provider.isBackoffActive());

        org.junit.jupiter.api.Assertions.assertThrows(UpstreamServiceException.class,
                () -> provider.enrichMovie(movie()));
        assertEquals(2, requestCount.get());
    }

    private OmdbProvider provider() {
        return provider(Clock.systemUTC());
    }

    private OmdbProvider provider(Clock clock) {
        return new OmdbProvider(
                "test-key",
                "http://localhost:" + server.getAddress().getPort(),
                Duration.ofHours(6),
                new ObjectMapper(),
                clock);
    }

    private static MovieEntry movie() {
        MovieEntry movie = new MovieEntry();
        movie.imdbId = "tt0000042";
        return movie;
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
