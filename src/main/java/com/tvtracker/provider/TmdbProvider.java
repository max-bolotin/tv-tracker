package com.tvtracker.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class TmdbProvider implements MetadataProvider {

    private final String apiKey;
    private final String baseUrl;
    private final String imageBaseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper;

    public TmdbProvider(
            @Value("${app.tmdb.api-key}") String apiKey,
            @Value("${app.tmdb.base-url}") String baseUrl,
            @Value("${app.tmdb.image-base-url}") String imageBaseUrl,
            ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.imageBaseUrl = imageBaseUrl;
        this.mapper = mapper;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<ShowSearchResult> search(String query) {
        try {
            String url = UriComponentsBuilder.fromUriString(baseUrl + "/search/tv")
                    .queryParam("api_key", apiKey)
                    .queryParam("query", query)
                    .toUriString();
            JsonNode root = get(url);
            List<ShowSearchResult> results = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                ShowSearchResult r = new ShowSearchResult();
                r.tmdbId = item.path("id").asLong();
                r.title = item.path("name").asText();
                r.overview = item.path("overview").asText();
                String poster = item.path("poster_path").asText(null);
                r.posterPath = poster != null ? imageBaseUrl + poster : null;
                results.add(r);
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("TMDB search failed", e);
        }
    }

    @Override
    public TrackedShow fetchDetails(long tmdbId) {
        try {
            String url = UriComponentsBuilder.fromUriString(baseUrl + "/tv/" + tmdbId)
                    .queryParam("api_key", apiKey)
                    .toUriString();
            JsonNode root = get(url);

            TrackedShow show = new TrackedShow();
            show.tmdbId = tmdbId;
            show.title = root.path("name").asText();
            show.overview = root.path("overview").asText();
            show.totalSeasons = root.path("number_of_seasons").asInt();
            String poster = root.path("poster_path").asText(null);
            show.posterPath = poster != null ? imageBaseUrl + poster : null;
            String status = root.path("status").asText("");
            show.productionStatus = status.equalsIgnoreCase("Ended") || status.equalsIgnoreCase("Canceled")
                    ? ProductionStatus.ENDED : ProductionStatus.ONGOING;

            // Fetch each season
            for (int s = 1; s <= show.totalSeasons; s++) {
                Season season = fetchSeason(tmdbId, s);
                if (season != null) show.seasons.add(season);
            }
            return show;
        } catch (Exception e) {
            throw new RuntimeException("TMDB fetchDetails failed for id=" + tmdbId, e);
        }
    }

    private Season fetchSeason(long tmdbId, int seasonNumber) {
        try {
            String url = UriComponentsBuilder.fromUriString(baseUrl + "/tv/" + tmdbId + "/season/" + seasonNumber)
                    .queryParam("api_key", apiKey)
                    .toUriString();
            JsonNode root = get(url);
            Season season = new Season(seasonNumber);
            for (JsonNode ep : root.path("episodes")) {
                String airDate = ep.path("air_date").asText(null);
                season.episodes.add(new Episode(
                        ep.path("episode_number").asInt(),
                        ep.path("name").asText(),
                        airDate
                ));
            }
            return season;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Long> fetchRecentlyUpdatedIds() {
        try {
            String yesterday = LocalDate.now().minusDays(1).toString();
            String url = UriComponentsBuilder.fromUriString(baseUrl + "/tv/changes")
                    .queryParam("api_key", apiKey)
                    .queryParam("start_date", yesterday)
                    .toUriString();
            JsonNode root = get(url);
            List<Long> ids = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                ids.add(item.path("id").asLong());
            }
            return ids;
        } catch (Exception e) {
            throw new RuntimeException("TMDB fetchRecentlyUpdatedIds failed", e);
        }
    }

    public List<ShowSearchResult> fetchPopular(int limit) {
        try {
            List<ShowSearchResult> results = new ArrayList<>();
            int page = 1;
            while (results.size() < limit) {
                String url = UriComponentsBuilder.fromUriString(baseUrl + "/tv/popular")
                        .queryParam("api_key", apiKey)
                        .queryParam("page", page)
                        .toUriString();
                JsonNode root = get(url);
                JsonNode arr = root.path("results");
                if (!arr.isArray() || arr.isEmpty()) break;
                for (JsonNode item : arr) {
                    if (results.size() >= limit) break;
                    ShowSearchResult r = new ShowSearchResult();
                    r.tmdbId = item.path("id").asLong();
                    r.title = item.path("name").asText();
                    r.overview = item.path("overview").asText();
                    String poster = item.path("poster_path").asText(null);
                    r.posterPath = poster != null ? imageBaseUrl + poster : null;
                    // totalSeasons and productionStatus are not available in list responses; leave defaults
                    results.add(r);
                }
                // if we've exhausted this page and still need more, increment page. TMDB pages up to total_pages
                page++;
                // safety guard: don't loop forever
                if (page > 10) break; // avoid too many pages in case of bad API
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("TMDB fetchPopular failed", e);
        }
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body());
    }
}
