package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrackedShow {
    public String id;           // internal UUID
    public Long tmdbId;
    public Long tvmazeId;
    public String imdbId;
    public String title;
    public String posterPath;   // relative path or full URL
    public String overview;
    public int totalSeasons;
    public ProductionStatus productionStatus = ProductionStatus.ONGOING;
    public WatchStatus watchStatus = WatchStatus.NOT_WATCHED;
    public List<Season> seasons = new ArrayList<>();

    public TrackedShow() {}

    // --- Tier recalculation logic ---
    public void recalculateStatus() {
        if (watchStatus == WatchStatus.NOT_WATCHED || watchStatus == WatchStatus.DROPPED) return;

        boolean allWatched = seasons.stream().allMatch(Season::allWatched);
        boolean anyWatched = seasons.stream().anyMatch(s -> s.episodes.stream().anyMatch(e -> e.watched));

        if (!anyWatched) {
            watchStatus = WatchStatus.NOT_WATCHED;
        } else if (allWatched && productionStatus == ProductionStatus.ENDED) {
            watchStatus = WatchStatus.FINISHED;
        } else if (allWatched && productionStatus == ProductionStatus.ONGOING) {
            watchStatus = WatchStatus.UP_TO_DATE;
        } else {
            watchStatus = WatchStatus.WATCHING_NOW;
        }
    }

    // Getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getTmdbId() { return tmdbId; }
    public void setTmdbId(Long tmdbId) { this.tmdbId = tmdbId; }
    public Long getTvmazeId() { return tvmazeId; }
    public void setTvmazeId(Long tvmazeId) { this.tvmazeId = tvmazeId; }
    public String getImdbId() { return imdbId; }
    public void setImdbId(String imdbId) { this.imdbId = imdbId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPosterPath() { return posterPath; }
    public void setPosterPath(String posterPath) { this.posterPath = posterPath; }
    public String getOverview() { return overview; }
    public void setOverview(String overview) { this.overview = overview; }
    public int getTotalSeasons() { return totalSeasons; }
    public void setTotalSeasons(int totalSeasons) { this.totalSeasons = totalSeasons; }
    public ProductionStatus getProductionStatus() { return productionStatus; }
    public void setProductionStatus(ProductionStatus productionStatus) { this.productionStatus = productionStatus; }
    public WatchStatus getWatchStatus() { return watchStatus; }
    public void setWatchStatus(WatchStatus watchStatus) { this.watchStatus = watchStatus; }
    public List<Season> getSeasons() { return seasons; }
    public void setSeasons(List<Season> seasons) { this.seasons = seasons; }
}
