package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Episode {
    public int number;
    public String name;
    public boolean watched;

    public Episode() {}

    public Episode(int number, String name) {
        this.number = number;
        this.name = name;
        this.watched = false;
    }

    // Getters/setters for Jackson
    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isWatched() { return watched; }
    public void setWatched(boolean watched) { this.watched = watched; }
}
