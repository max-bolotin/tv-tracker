package com.tvtracker.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Component
public class TvMazeProvider implements MetadataProvider {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper;

    public TvMazeProvider(@Value("${app.tvmaze.base-url}") String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    @Override
    public List<ShowSearchResult> search(String query) {
        try {
            String url = baseUrl + "/search/shows?q=" + java.net.URLEncoder.encode(query,
                StandardCharsets.UTF_8);
            JsonNode root = get(url);
            List<ShowSearchResult> results = new ArrayList<>();
            for (JsonNode item : root) {
                JsonNode show = item.path("show");
                ShowSearchResult r = new ShowSearchResult();
                r.tvmazeId = show.path("id").asLong();
                r.title = show.path("name").asText();
                r.overview = show.path("summary").asText("").replaceAll("<[^>]+>", "");
                r.posterPath = show.path("image").path("medium").asText(null);
                String status = show.path("status").asText("");
                r.productionStatus = status.equalsIgnoreCase("Ended") ? ProductionStatus.ENDED : ProductionStatus.ONGOING;
                results.add(r);
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("TVMaze search failed", e);
        }
    }

    @Override
    public TrackedShow fetchDetails(long tvmazeId) {
        try {
            JsonNode root = get(baseUrl + "/shows/" + tvmazeId + "?embed=episodes");
            TrackedShow show = new TrackedShow();
            show.tvmazeId = tvmazeId;
            show.title = root.path("name").asText();
            show.overview = root.path("summary").asText("").replaceAll("<[^>]+>", "");
            show.posterPath = root.path("image").path("original").asText(null);
            String status = root.path("status").asText("");
            show.productionStatus = status.equalsIgnoreCase("Ended") ? ProductionStatus.ENDED : ProductionStatus.ONGOING;

            // Build seasons. Use the seasons endpoint so we include seasons even if they have no episodes yet.
            JsonNode seasonsRoot = get(baseUrl + "/shows/" + tvmazeId + "/seasons");
            List<Season> seasons = new ArrayList<>();
            for (JsonNode sNode : seasonsRoot) {
                int sNum = sNode.path("number").asInt();
                Season season = new Season(sNum);
                // fetch episodes for this season (may return empty array)
                try {
                    JsonNode eps = get(baseUrl + "/seasons/" + sNode.path("id").asInt() + "/episodes");
                    for (JsonNode ep : eps) {
                        int eNum = ep.path("number").asInt();
                        String airDate = ep.path("airdate").asText(null);
                        if (airDate != null && airDate.isBlank()) airDate = null;
                        season.episodes.add(new Episode(eNum, ep.path("name").asText(), airDate));
                    }
                } catch (Exception ignore) {
                    // treat as empty episode list
                }
                seasons.add(season);
            }
            show.seasons = seasons;
            show.totalSeasons = show.seasons.size();
            return show;
        } catch (Exception e) {
            throw new RuntimeException("TVMaze fetchDetails failed for id=" + tvmazeId, e);
        }
    }

    @Override
    public List<Long> fetchRecentlyUpdatedIds() {
        try {
            JsonNode root = get(baseUrl + "/updates/shows?since=day");
            List<Long> ids = new ArrayList<>();
            root.fieldNames().forEachRemaining(id -> ids.add(Long.parseLong(id)));
            return ids;
        } catch (Exception e) {
            throw new RuntimeException("TVMaze fetchRecentlyUpdatedIds failed", e);
        }
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body());
    }
}
