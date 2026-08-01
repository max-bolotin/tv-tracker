package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;

// Lightweight DTO returned by search endpoints (not persisted)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShowSearchResult {
    public Long tmdbId;
    public Long tvmazeId;
    public String imdbId;
    public String title;
    public String posterPath;
    public String overview;
    public int totalSeasons;
    public ProductionStatus productionStatus;

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
}
