package com.tvtracker.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.exception.UpstreamServiceException;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.Season;
import com.tvtracker.model.TrackedShow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OmdbProvider {

    private static final Logger log = LoggerFactory.getLogger(OmdbProvider.class);

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper;
    private final Duration backoffDuration;
    private final Clock clock;
    private volatile Instant backoffUntil;

    public OmdbProvider(
            @Value("${app.omdb.api-key:}") String apiKey,
            @Value("${app.omdb.base-url:https://www.omdbapi.com}") String baseUrl,
            ObjectMapper mapper) {
        this(apiKey, baseUrl, Duration.ofHours(6), mapper, Clock.systemUTC());
    }

    @Autowired
    public OmdbProvider(
            @Value("${app.omdb.api-key:}") String apiKey,
            @Value("${app.omdb.base-url:https://www.omdbapi.com}") String baseUrl,
            @Value("${app.omdb.backoff:PT6H}") Duration backoffDuration,
            ObjectMapper mapper,
            Clock clock) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.backoffDuration = backoffDuration;
        this.mapper = mapper;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @jakarta.annotation.PostConstruct
    void logConfigStatus() {
        if (!isConfigured()) {
            log.warn("OMDb API key not configured (OMDB_API_KEY) — IMDb/RT ratings will not be fetched");
        } else {
            log.info("OMDb provider configured");
        }
    }

    /**
     * Fetches show-level IMDb and RT ratings and writes them into the show.
     * Skips if ratings are fresh (updated within 6 months).
     */
    public void enrichShow(TrackedShow show) {
        if (!isConfigured() || show.imdbId == null) return;
        if (isBackoffActive()) return;
        if (isRatingFresh(show.ratingsUpdatedAt)) return;
        try {
            JsonNode root = get(baseUrl + "/?apikey=" + apiKey + "&i=" + show.imdbId + "&type=series");
            if (!"True".equals(root.path("Response").asText())) {
                log.debug("OMDb no result for show imdbId={}", show.imdbId);
                return;
            }
            show.imdbRating = parseImdb(root);
            show.rtRating = parseRt(root);
            show.ratingsUpdatedAt = LocalDate.now(clock).toString();
            log.debug("OMDb enriched show '{}': imdb={} rt={}", show.title, show.imdbRating, show.rtRating);
        } catch (OmdbBackoffException | OmdbAuthenticationException e) {
            // The provider has already logged the quota or authentication failure.
        } catch (Exception e) {
            log.warn("OMDb enrichShow failed for '{}': {}", show.title, e.getMessage());
        }
    }

    public void enrichMovie(MovieEntry movie) {
        if (!isConfigured() || movie.imdbId == null || movie.imdbId.isBlank()) {
            return;
        }
        AtomicBoolean requestAttempted = new AtomicBoolean();
        try {
            String url = UriComponentsBuilder.fromUriString(baseUrl)
                    .queryParam("apikey", apiKey)
                    .queryParam("i", movie.imdbId)
                    .queryParam("type", "movie")
                    .build()
                    .encode()
                    .toUriString();
            JsonNode root = get(url, requestAttempted);
            if (!"True".equals(root.path("Response").asText())) {
                log.debug("OMDb no result for movie imdbId={}", movie.imdbId);
                return;
            }
            movie.imdbRating = parseImdb(root);
            movie.rottenTomatoesScore = parseRt(root);
            log.debug("OMDb enriched movie '{}': imdb={} rt={}",
                    movie.title, movie.imdbRating, movie.rottenTomatoesScore);
        } catch (Exception e) {
            boolean attempted = e instanceof OmdbBackoffException backoffException
                    ? backoffException.requestAttempted
                    : requestAttempted.get();
            throw new MovieEnrichmentException(
                    "OMDb movie enrichment failed for '" + movie.title + "'", attempted, e);
        }
    }

    public static class MovieEnrichmentException extends UpstreamServiceException {
        private final boolean requestAttempted;

        private MovieEnrichmentException(String message, boolean requestAttempted, Throwable cause) {
            super(message, cause);
            this.requestAttempted = requestAttempted;
        }

        public boolean requestAttempted() {
            return requestAttempted;
        }
    }

    /**
     * Fetches season-level IMDb and RT ratings and writes them into the season.
     * Skips if ratings are fresh (updated within 6 months).
     */
    public void enrichSeason(TrackedShow show, Season season) {
        if (!isConfigured() || show.imdbId == null) return;
        if (isBackoffActive()) return;
        if (isRatingFresh(season.ratingsUpdatedAt)) return;
        try {
            JsonNode root = get(baseUrl + "/?apikey=" + apiKey + "&i=" + show.imdbId
                    + "&type=series&Season=" + season.number);
            if (!"True".equals(root.path("Response").asText())) {
                log.debug("OMDb no result for show imdbId={} season={}", show.imdbId, season.number);
                return;
            }
            season.imdbRating = parseImdb(root);
            season.rtRating = parseRt(root);
            season.ratingsUpdatedAt = LocalDate.now(clock).toString();
            log.debug("OMDb enriched show '{}' season {}: imdb={} rt={}", show.title, season.number,
                    season.imdbRating, season.rtRating);
        } catch (OmdbBackoffException | OmdbAuthenticationException e) {
            // The provider has already logged the quota or authentication failure.
        } catch (Exception e) {
            log.warn("OMDb enrichSeason failed for '{}' s{}: {}", show.title, season.number, e.getMessage());
        }
    }

    /** Returns true if the ratingsUpdatedAt date is within the last 6 months. */
    private boolean isRatingFresh(String updatedAt) {
        if (updatedAt == null) return false;
        try {
            return LocalDate.parse(updatedAt).isAfter(LocalDate.now(clock).minusMonths(6));
        } catch (Exception e) {
            return false;
        }
    }

    private String parseImdb(JsonNode root) {
        String rating = root.path("imdbRating").asText(null);
        if (rating == null || rating.isBlank() || "N/A".equals(rating)) return null;
        return rating;
    }

    private String parseRt(JsonNode root) {
        JsonNode ratings = root.path("Ratings");
        if (ratings.isArray()) {
            for (JsonNode r : ratings) {
                if ("Rotten Tomatoes".equals(r.path("Source").asText())) {
                    String val = r.path("Value").asText(null);
                    if (val != null && !val.isBlank() && !"N/A".equals(val)) return val;
                }
            }
        }
        return null;
    }

    private JsonNode get(String url) throws Exception {
        return get(url, null);
    }

    private JsonNode get(String url, AtomicBoolean requestAttempted) throws Exception {
        if (isBackoffActive()) {
            throw new OmdbBackoffException(false);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        if (requestAttempted != null) {
            requestAttempted.set(true);
        }
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String responseText = resp.body();
        JsonNode body;
        try {
            body = mapper.readTree(responseText);
        } catch (Exception parseFailure) {
            if (responseText.contains("Request limit reached!")) {
                startBackoff();
                throw new OmdbBackoffException(true);
            }
            if (resp.statusCode() == 401) {
                throw rejectApiKey(responseText);
            }
            if (resp.statusCode() != 200) {
                throw new RuntimeException("HTTP " + resp.statusCode() + " - " + responseText, parseFailure);
            }
            throw parseFailure;
        }
        String error = body.path("Error").asText("");
        if (error.contains("Request limit reached!") || responseText.contains("Request limit reached!")) {
            startBackoff();
            throw new OmdbBackoffException(true);
        }
        if (resp.statusCode() != 200) {
            if (resp.statusCode() == 401) {
                throw rejectApiKey(error.isBlank() ? responseText : error);
            }
            throw new RuntimeException("HTTP " + resp.statusCode() + " - " + resp.body());
        }
        return body;
    }

    private OmdbAuthenticationException rejectApiKey(String detail) {
        log.error("OMDb rejected the API key (HTTP 401): {}", detail);
        return new OmdbAuthenticationException("OMDb authentication failed");
    }

    public boolean isBackoffActive() {
        Instant until = backoffUntil;
        return until != null && clock.instant().isBefore(until);
    }

    private synchronized void startBackoff() {
        Instant now = clock.instant();
        if (backoffUntil == null || !now.isBefore(backoffUntil)) {
            backoffUntil = now.plus(backoffDuration);
            log.warn("OMDb request quota reached; suppressing requests until {}", backoffUntil);
        }
    }

    private static final class OmdbBackoffException extends RuntimeException {
        private final boolean requestAttempted;

        private OmdbBackoffException(boolean requestAttempted) {
            this.requestAttempted = requestAttempted;
        }
    }

    private static final class OmdbAuthenticationException extends RuntimeException {
        private OmdbAuthenticationException(String message) {
            super(message);
        }
    }
}
