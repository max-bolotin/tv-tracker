package com.tvtracker.provider;

import com.tvtracker.model.Season;
import com.tvtracker.model.TrackedShow;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class MetadataServiceEnrichRatingsTest {

    private TmdbProvider tmdb;
    private TvMazeProvider tvmaze;
    private OmdbProvider omdb;
    private MetadataService service;

    @BeforeEach
    void setUp() {
        tmdb = mock(TmdbProvider.class);
        tvmaze = mock(TvMazeProvider.class);
        omdb = mock(OmdbProvider.class);
        service = new MetadataService(tmdb, tvmaze, omdb);
    }

    private TrackedShow showWithSeasons(String imdbId, Long tmdbId, int... seasonNumbers) {
        TrackedShow show = new TrackedShow();
        show.imdbId = imdbId;
        show.tmdbId = tmdbId;
        show.title = "Test Show";
        show.seasons = new ArrayList<>();
        for (int n : seasonNumbers) show.seasons.add(new Season(n));
        return show;
    }

    // ---- skips entirely when omdb not configured ----

    @Test
    void enrichRatings_doesNothing_whenOmdbNotConfigured() {
        when(omdb.isConfigured()).thenReturn(false);
        TrackedShow show = showWithSeasons("tt123", 1L, 1);

        service.enrichRatings(show);

        verify(omdb, never()).enrichShow(any());
        verify(omdb, never()).enrichSeason(any(), any());
        verify(tmdb, never()).fetchImdbId(anyLong());
    }

    // ---- uses existing imdbId without TMDB lookup ----

    @Test
    void enrichRatings_usesExistingImdbId_withoutTmdbLookup() {
        when(omdb.isConfigured()).thenReturn(true);
        TrackedShow show = showWithSeasons("tt1234567", 42L, 1, 2);

        service.enrichRatings(show);

        verify(tmdb, never()).fetchImdbId(anyLong());
        verify(omdb).enrichShow(show);
        verify(omdb).enrichSeason(show, show.seasons.get(0));
        verify(omdb).enrichSeason(show, show.seasons.get(1));
    }

    // ---- resolves imdbId via TMDB when missing ----

    @Test
    void enrichRatings_resolvesImdbIdViaTmdb_whenMissing() {
        when(omdb.isConfigured()).thenReturn(true);
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchImdbId(99L)).thenReturn("tt9999999");
        TrackedShow show = showWithSeasons(null, 99L, 1);

        service.enrichRatings(show);

        verify(tmdb).fetchImdbId(99L);
        verify(omdb).enrichShow(show);
        verify(omdb).enrichSeason(eq(show), any(Season.class));
    }

    // ---- aborts when imdbId still null after TMDB lookup ----

    @Test
    void enrichRatings_abortsEarly_whenImdbIdStillNullAfterTmdbLookup() {
        when(omdb.isConfigured()).thenReturn(true);
        when(tmdb.isConfigured()).thenReturn(true);
        when(tmdb.fetchImdbId(anyLong())).thenReturn(null);
        TrackedShow show = showWithSeasons(null, 5L, 1);

        service.enrichRatings(show);

        verify(omdb, never()).enrichShow(any());
        verify(omdb, never()).enrichSeason(any(), any());
    }

    // ---- aborts when imdbId null and no tmdbId ----

    @Test
    void enrichRatings_abortsEarly_whenImdbIdNullAndNoTmdbId() {
        when(omdb.isConfigured()).thenReturn(true);
        TrackedShow show = showWithSeasons(null, null, 1);

        service.enrichRatings(show);

        verify(tmdb, never()).fetchImdbId(anyLong());
        verify(omdb, never()).enrichShow(any());
    }

    // ---- enriches each season individually ----

    @Test
    void enrichRatings_callsEnrichSeasonForEachSeason() {
        when(omdb.isConfigured()).thenReturn(true);
        TrackedShow show = showWithSeasons("tt111", null, 1, 2, 3);

        service.enrichRatings(show);

        verify(omdb, times(3)).enrichSeason(eq(show), any(Season.class));
    }

    // ---- swallows unexpected exceptions ----

    @Test
    void enrichRatings_swallowsException_fromEnrichShow() {
        when(omdb.isConfigured()).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(omdb).enrichShow(any());
        TrackedShow show = showWithSeasons("tt123", null, 1);

        service.enrichRatings(show); // must not throw
    }
}
