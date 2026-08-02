package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Human-friendly import/export format. */
public class ImportExportPayload {

    @JsonProperty("shows")
    public List<ImportShow> shows;

    @JsonProperty("watchlist_shows")
    public List<ImportShow> watchlistShows;

    @JsonProperty("up_to_date_shows")
    public List<ImportShow> upToDateShows;

    @JsonProperty("finished_shows")
    public List<ImportShow> finishedShows;

    @JsonProperty("stopped_shows")
    public List<ImportShow> stoppedShows;

    public static class ImportShow {
        public String title;
        public Integer year;

        @JsonProperty("watched_seasons")
        public List<Integer> watchedSeasons;

        /** Full episode-level detail — present in exports, optional in hand-written imports */
        @JsonProperty("seasons")
        public List<SeasonDetail> seasons;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public Integer getYear() { return year; }
        public void setYear(Integer year) { this.year = year; }
        public List<Integer> getWatchedSeasons() { return watchedSeasons; }
        public void setWatchedSeasons(List<Integer> watchedSeasons) { this.watchedSeasons = watchedSeasons; }
        public List<SeasonDetail> getSeasons() { return seasons; }
        public void setSeasons(List<SeasonDetail> seasons) { this.seasons = seasons; }
    }

    public static class SeasonDetail {
        public int number;
        public List<EpisodeDetail> episodes;

        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }
        public List<EpisodeDetail> getEpisodes() { return episodes; }
        public void setEpisodes(List<EpisodeDetail> episodes) { this.episodes = episodes; }
    }

    public static class EpisodeDetail {
        public int number;
        public String name;
        public boolean watched;
        @JsonProperty("air_date")
        public String airDate;

        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isWatched() { return watched; }
        public void setWatched(boolean watched) { this.watched = watched; }
        public String getAirDate() { return airDate; }
        public void setAirDate(String airDate) { this.airDate = airDate; }
    }

    public List<ImportShow> getShows() { return shows; }
    public void setShows(List<ImportShow> shows) { this.shows = shows; }
    public List<ImportShow> getWatchlistShows() { return watchlistShows; }
    public void setWatchlistShows(List<ImportShow> watchlistShows) { this.watchlistShows = watchlistShows; }
    public List<ImportShow> getUpToDateShows() { return upToDateShows; }
    public void setUpToDateShows(List<ImportShow> upToDateShows) { this.upToDateShows = upToDateShows; }
    public List<ImportShow> getFinishedShows() { return finishedShows; }
    public void setFinishedShows(List<ImportShow> finishedShows) { this.finishedShows = finishedShows; }
    public List<ImportShow> getStoppedShows() { return stoppedShows; }
    public void setStoppedShows(List<ImportShow> stoppedShows) { this.stoppedShows = stoppedShows; }
}
