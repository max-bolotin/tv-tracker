package com.tvtracker.provider;

import com.tvtracker.exception.UpstreamServiceException;
import com.tvtracker.model.Actor;
import com.tvtracker.model.MovieEntry;
import com.tvtracker.model.MovieStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class MetadataServiceMovieTest {
    @Test
    void movieDetailsCacheReturnsDefensiveCopies() {
        TmdbProvider tmdb = mock(TmdbProvider.class);
        TvMazeProvider tvmaze = mock(TvMazeProvider.class);
        OmdbProvider omdb = mock(OmdbProvider.class);
        MovieEntry source = new MovieEntry();
        source.title = "Arrival";
        source.tmdbId = 42L;
        source.cast = List.of(new Actor("Amy Adams", "/amy.jpg"));
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchMovieDetails(42L)).thenReturn(source);
        when(omdb.isConfigured()).thenReturn(false);
        MetadataService service = new MetadataService(tmdb, tvmaze, omdb);

        MovieEntry first = service.fetchMovieDetails(42L).movie();
        first.id = "user-specific-id";
        first.status = MovieStatus.WATCHED;
        first.lastRefreshedAt = java.time.Instant.now();
        first.title = "Changed";
        first.cast.getFirst().name = "Changed Actor";

        MovieEntry second = service.fetchMovieDetails(42L).movie();

        assertThat(second).isNotSameAs(first);
        assertThat(second.id).isNull();
        assertThat(second.status).isEqualTo(MovieStatus.PLANNED);
        assertThat(second.lastRefreshedAt).isNull();
        assertThat(second.title).isEqualTo("Arrival");
        assertThat(second.cast.getFirst().name).isEqualTo("Amy Adams");
        verify(tmdb, times(1)).fetchMovieDetails(42L);
    }

    @Test
    void cachedMovieWithoutRatingsRetriesOmdbAndKeepsCachedMetadataOnFailure() {
        TmdbProvider tmdb = mock(TmdbProvider.class);
        TvMazeProvider tvmaze = mock(TvMazeProvider.class);
        OmdbProvider omdb = mock(OmdbProvider.class);
        MovieEntry source = new MovieEntry();
        source.title = "Arrival";
        source.tmdbId = 42L;
        source.imdbId = "tt2543164";
        source.releaseDate = "2016-01-01";
        source.overview = "TMDB overview";
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchMovieDetails(42L)).thenReturn(source);
        when(omdb.isConfigured()).thenReturn(true);
        doThrow(new UpstreamServiceException("OMDb unavailable"))
                .when(omdb).enrichMovie(any());
        MetadataService service = new MetadataService(tmdb, tvmaze, omdb);

        var first = service.fetchMovieDetails(42L);
        var retry = service.fetchMovieDetails(42L);

        assertThat(first.ratingsRefreshed()).isFalse();
        assertThat(retry.ratingsRefreshed()).isFalse();
        assertThat(retry.movie().title).isEqualTo("Arrival");
        assertThat(retry.movie().overview).isEqualTo("TMDB overview");
        assertThat(retry.movie().imdbRating).isNull();
        verify(tmdb, times(1)).fetchMovieDetails(42L);
        verify(omdb, times(2)).enrichMovie(any());
    }

    @Test
    void movieDetailsDoNotCallOmdbForUndatedMovies() {
        TmdbProvider tmdb = mock(TmdbProvider.class);
        OmdbProvider omdb = mock(OmdbProvider.class);
        MovieEntry movie = new MovieEntry();
        movie.tmdbId = 42L;
        movie.imdbId = "tt1234567";
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchMovieDetails(42L)).thenReturn(movie);
        when(omdb.isConfigured()).thenReturn(true);

        new MetadataService(tmdb, mock(TvMazeProvider.class), omdb).fetchMovieDetails(42L);

        verify(omdb, never()).enrichMovie(any());
    }

    @Test
    void omdbFailureDoesNotFailMovieDetailsFetch() {
        TmdbProvider tmdb = mock(TmdbProvider.class);
        TvMazeProvider tvmaze = mock(TvMazeProvider.class);
        OmdbProvider omdb = mock(OmdbProvider.class);
        MovieEntry movie = new MovieEntry();
        movie.title = "Arrival";
        movie.tmdbId = 42L;
        movie.imdbId = "tt1234567";
        movie.releaseDate = "2016-01-01";
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchMovieDetails(42L)).thenReturn(movie);
        when(omdb.isConfigured()).thenReturn(true);
        doThrow(new UpstreamServiceException("OMDb unavailable")).when(omdb).enrichMovie(movie);

        MetadataService.MovieMetadataResult result =
                new MetadataService(tmdb, tvmaze, omdb).fetchMovieDetails(42L);

        assertThat(result.movie()).isNotSameAs(movie);
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

        assertThat(result.movie()).isNotSameAs(movie);
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
