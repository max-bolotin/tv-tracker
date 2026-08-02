package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Episode {
    public int number;
    public String name;
    public boolean watched;
    public String airDate; // ISO-8601 date string, e.g. "2024-03-15", null if unknown

    public Episode() {}

    public Episode(int number, String name, String airDate) {
        this.number = number;
        this.name = name;
        this.airDate = airDate;
        this.watched = false;
    }

    // Getters/setters for Jackson
    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isWatched() { return watched; }
    public void setWatched(boolean watched) { this.watched = watched; }
    public String getAirDate() { return airDate; }
    public void setAirDate(String airDate) { this.airDate = airDate; }
}
