package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MovieSearchResult {
    public Long tmdbId;
    public String title;
    public String releaseDate;
    public String poster;
    public String overview;
}
