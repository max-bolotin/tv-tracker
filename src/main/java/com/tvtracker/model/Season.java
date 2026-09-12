package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Season {
    public int number;
    public List<Episode> episodes = new ArrayList<>();
    public String imdbRating;        // e.g. "7.9" or null
    public String rtRating;          // e.g. "88%" or null
    public String ratingsUpdatedAt;  // ISO-8601 date string, null if never fetched

    public Season() {}

    public Season(int number) {
        this.number = number;
    }

    public boolean allWatched() {
        return !episodes.isEmpty() && episodes.stream().allMatch(e -> e.watched);
    }

    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }
    public List<Episode> getEpisodes() { return episodes; }
    public void setEpisodes(List<Episode> episodes) { this.episodes = episodes; }
}
