package com.tvtracker.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.Season;
import com.tvtracker.model.TrackedShow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

@Component
public class OmdbProvider {

    private static final Logger log = LoggerFactory.getLogger(OmdbProvider.class);

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper;

    public OmdbProvider(
            @Value("${app.omdb.api-key:}") String apiKey,
            @Value("${app.omdb.base-url:https://www.omdbapi.com}") String baseUrl,
            ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.mapper = mapper;
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
        if (isRatingFresh(show.ratingsUpdatedAt)) return;
        try {
            JsonNode root = get(baseUrl + "/?apikey=" + apiKey + "&i=" + show.imdbId + "&type=series");
            if (!"True".equals(root.path("Response").asText())) {
                log.info("OMDb no result for show imdbId={}", show.imdbId);
                return;
            }
            show.imdbRating = parseImdb(root);
            show.rtRating = parseRt(root);
            show.ratingsUpdatedAt = LocalDate.now().toString();
            log.info("OMDb enriched show '{}': imdb={} rt={}", show.title, show.imdbRating, show.rtRating);
        } catch (Exception e) {
            log.warn("OMDb enrichShow failed for '{}': {}", show.title, e.getMessage());
        }
    }

    /**
     * Fetches season-level IMDb and RT ratings and writes them into the season.
     * Skips if ratings are fresh (updated within 6 months).
     */
    public void enrichSeason(TrackedShow show, Season season) {
        if (!isConfigured() || show.imdbId == null) return;
        if (isRatingFresh(season.ratingsUpdatedAt)) return;
        try {
            JsonNode root = get(baseUrl + "/?apikey=" + apiKey + "&i=" + show.imdbId
                    + "&type=series&Season=" + season.number);
            if (!"True".equals(root.path("Response").asText())) {
                log.info("OMDb no result for show imdbId={} season={}", show.imdbId, season.number);
                return;
            }
            season.imdbRating = parseImdb(root);
            season.rtRating = parseRt(root);
            season.ratingsUpdatedAt = LocalDate.now().toString();
            log.info("OMDb enriched show '{}' season {}: imdb={} rt={}", show.title, season.number,
                    season.imdbRating, season.rtRating);
        } catch (Exception e) {
            log.warn("OMDb enrichSeason failed for '{}' s{}: {}", show.title, season.number, e.getMessage());
        }
    }

    /** Returns true if the ratingsUpdatedAt date is within the last 6 months. */
    private boolean isRatingFresh(String updatedAt) {
        if (updatedAt == null) return false;
        try {
            return LocalDate.parse(updatedAt).isAfter(LocalDate.now().minusMonths(6));
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
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body());
    }
}
