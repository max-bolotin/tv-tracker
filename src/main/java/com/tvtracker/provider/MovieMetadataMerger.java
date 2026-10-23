package com.tvtracker.provider;

import com.tvtracker.model.MovieEntry;

public final class MovieMetadataMerger {
    private MovieMetadataMerger() {}

    public static void merge(MovieEntry current, MovieEntry fresh) {
        if (fresh.tmdbId != null) {
            current.tmdbId = fresh.tmdbId;
        }
        if (fresh.mediaType != null) {
            current.mediaType = fresh.mediaType;
        }
        if (fresh.imdbId != null) {
            current.imdbId = fresh.imdbId;
        }
        if (fresh.title != null) {
            current.title = fresh.title;
        }
        if (fresh.overview != null && !fresh.overview.isBlank()) {
            current.overview = fresh.overview;
        }
        if (fresh.releaseDate != null) {
            current.releaseDate = fresh.releaseDate;
        }
        if (fresh.runtime != null) {
            current.runtime = fresh.runtime;
        }
        if (fresh.poster != null && !fresh.poster.isBlank()) {
            current.poster = fresh.poster;
        }
        if (fresh.cast != null && !fresh.cast.isEmpty()) {
            current.cast = fresh.cast;
        }
        if (fresh.imdbRating != null) {
            current.imdbRating = fresh.imdbRating;
        }
        if (fresh.rottenTomatoesScore != null) {
            current.rottenTomatoesScore = fresh.rottenTomatoesScore;
        }
    }
}
