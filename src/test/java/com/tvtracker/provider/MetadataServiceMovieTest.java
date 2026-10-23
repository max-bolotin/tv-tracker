package com.tvtracker.provider;

import com.tvtracker.exception.UpstreamServiceException;
import com.tvtracker.model.MovieEntry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class MetadataServiceMovieTest {
    @Test
    void omdbFailureDoesNotFailMovieDetailsFetch() {
        TmdbProvider tmdb = mock(TmdbProvider.class);
        TvMazeProvider tvmaze = mock(TvMazeProvider.class);
        OmdbProvider omdb = mock(OmdbProvider.class);
        MovieEntry movie = new MovieEntry();
        movie.title = "Arrival";
        movie.tmdbId = 42L;
        movie.imdbId = "tt1234567";
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchMovieDetails(42L)).thenReturn(movie);
        when(omdb.isConfigured()).thenReturn(true);
        doThrow(new UpstreamServiceException("OMDb unavailable")).when(omdb).enrichMovie(movie);

        MetadataService.MovieMetadataResult result =
                new MetadataService(tmdb, tvmaze, omdb).fetchMovieDetails(42L);

        assertThat(result.movie()).isSameAs(movie);
        assertThat(result.ratingsRefreshed()).isFalse();
        assertThat(result.movie().imdbRating).isNull();
        assertThat(result.movie().rottenTomatoesScore).isNull();
        verify(omdb).enrichMovie(movie);
    }

    @Test
    void refreshCanSkipOmdbWhenPerRunLimitIsReached() {
        TmdbProvider tmdb = mock(TmdbProvider.class);
        TvMazeProvider tvmaze = mock(TvMazeProvider.class);
        OmdbProvider omdb = mock(OmdbProvider.class);
        MovieEntry movie = new MovieEntry();
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchMovieDetails(42L)).thenReturn(movie);
        when(omdb.isConfigured()).thenReturn(true);

        MetadataService.MovieMetadataResult result =
                new MetadataService(tmdb, tvmaze, omdb).fetchMovieDetails(42L, false);

        assertThat(result.movie()).isSameAs(movie);
        assertThat(result.ratingsRefreshed()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(omdb);
    }

    @Test
    void omdbNotApplicableIsReportedAsRatingsRefreshed() {
        TmdbProvider tmdb = mock(TmdbProvider.class);
        OmdbProvider omdb = mock(OmdbProvider.class);
        MovieEntry movie = new MovieEntry();
        movie.tmdbId = 42L;
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchMovieDetails(42L)).thenReturn(movie);
        when(omdb.isConfigured()).thenReturn(true);

        MetadataService.MovieMetadataResult result =
                new MetadataService(tmdb, mock(TvMazeProvider.class), omdb).fetchMovieDetails(42L, false);

        assertThat(result.ratingsRefreshed()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(omdb);
    }
}
