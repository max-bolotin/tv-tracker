package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MovieEntry {
    public String id;
    public Long tmdbId;
    public MediaType mediaType = MediaType.MOVIE;
    public String imdbId;
    public String title;
    public String overview;
    public String releaseDate;
    public Integer runtime;
    public String poster;
    public List<Actor> cast = new ArrayList<>();
    public String imdbRating;
    public String rottenTomatoesScore;
    /** Personal rating from 0 to 10 in 0.5 increments, or null when unset. */
    public Double personalRating;
    public MovieStatus status = MovieStatus.PLANNED;
    public String watchedDate;
    public Instant lastRefreshedAt;

    public MovieEntry() {}

    @JsonSetter(value = "mediaType", nulls = Nulls.SKIP)
    public void setMediaType(MediaType mediaType) {
        this.mediaType = mediaType;
    }

    @JsonSetter(value = "status", nulls = Nulls.SKIP)
    public void setStatus(MovieStatus status) {
        this.status = status;
    }
}
