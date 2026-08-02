package com.tvtracker.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackedShowStatusTest {

    // --- helpers ---

    private static Episode ep(int n, boolean watched) {
        Episode e = new Episode(n, "Episode " + n, null);
        e.watched = watched;
        return e;
    }

    private static Season season(int n, Episode... episodes) {
        Season s = new Season(n);
        s.episodes = List.of(episodes);
        return s;
    }

    private static TrackedShow show(ProductionStatus prod, WatchStatus initial, Season... seasons) {
        TrackedShow show = new TrackedShow();
        show.productionStatus = prod;
        show.watchStatus = initial;
        show.seasons = new java.util.ArrayList<>(List.of(seasons));
        return show;
    }

    // --- recalculateStatus tests ---

    @Test
    void noEpisodesWatched_remainsNotWatched() {
        TrackedShow s = show(ProductionStatus.ONGOING, WatchStatus.NOT_WATCHED,
                season(1, ep(1, false), ep(2, false)));
        s.recalculateStatus();
        assertEquals(WatchStatus.NOT_WATCHED, s.watchStatus);
    }

    @Test
    void firstEpisodeWatched_movesToWatchingNow() {
        TrackedShow s = show(ProductionStatus.ONGOING, WatchStatus.NOT_WATCHED,
                season(1, ep(1, true), ep(2, false)));
        s.recalculateStatus();
        assertEquals(WatchStatus.WATCHING_NOW, s.watchStatus);
    }

    @Test
    void allWatched_ongoingShow_movesToUpToDate() {
        TrackedShow s = show(ProductionStatus.ONGOING, WatchStatus.WATCHING_NOW,
                season(1, ep(1, true), ep(2, true)),
                season(2, ep(1, true)));
        s.recalculateStatus();
        assertEquals(WatchStatus.UP_TO_DATE, s.watchStatus);
    }

    @Test
    void allWatched_endedShow_movesToFinished() {
        TrackedShow s = show(ProductionStatus.ENDED, WatchStatus.WATCHING_NOW,
                season(1, ep(1, true), ep(2, true)));
        s.recalculateStatus();
        assertEquals(WatchStatus.FINISHED, s.watchStatus);
    }

    @Test
    void partiallyWatched_staysWatchingNow() {
        TrackedShow s = show(ProductionStatus.ENDED, WatchStatus.WATCHING_NOW,
                season(1, ep(1, true), ep(2, false)));
        s.recalculateStatus();
        assertEquals(WatchStatus.WATCHING_NOW, s.watchStatus);
    }

    @Test
    void uncheckingLastEpisode_regressesFromWatchingNowToNotWatched() {
        TrackedShow s = show(ProductionStatus.ONGOING, WatchStatus.WATCHING_NOW,
                season(1, ep(1, false), ep(2, false)));
        s.recalculateStatus();
        assertEquals(WatchStatus.NOT_WATCHED, s.watchStatus);
    }

    @Test
    void uncheckingEpisode_regressesFromUpToDateToWatchingNow() {
        // Was UP_TO_DATE, user unchecks one episode
        TrackedShow s = show(ProductionStatus.ONGOING, WatchStatus.UP_TO_DATE,
                season(1, ep(1, true), ep(2, false)));
        s.recalculateStatus();
        assertEquals(WatchStatus.WATCHING_NOW, s.watchStatus);
    }

    @Test
    void droppedShow_isNeverRecalculated() {
        TrackedShow s = show(ProductionStatus.ONGOING, WatchStatus.DROPPED,
                season(1, ep(1, true), ep(2, true)));
        s.recalculateStatus();
        assertEquals(WatchStatus.DROPPED, s.watchStatus);
    }

    @Test
    void emptySeasonsList_treatedAsNotWatched() {
        TrackedShow s = new TrackedShow();
        s.productionStatus = ProductionStatus.ONGOING;
        s.watchStatus = WatchStatus.WATCHING_NOW;
        // no seasons
        s.recalculateStatus();
        assertEquals(WatchStatus.NOT_WATCHED, s.watchStatus);
    }

    @Test
    void seasonWithNoEpisodes_doesNotCountAsAllWatched() {
        // A season with zero episodes should not satisfy allWatched
        TrackedShow s = show(ProductionStatus.ENDED, WatchStatus.WATCHING_NOW,
                season(1, ep(1, true)),
                new Season(2) /* empty */);
        s.recalculateStatus();
        // season 2 has no episodes → allWatched() returns false → WATCHING_NOW, not FINISHED
        assertEquals(WatchStatus.WATCHING_NOW, s.watchStatus);
    }

    // --- Season.allWatched tests ---

    @Test
    void season_allWatched_trueWhenAllEpisodesWatched() {
        Season s = season(1, ep(1, true), ep(2, true));
        assertTrue(s.allWatched());
    }

    @Test
    void season_allWatched_falseWhenAnyEpisodeUnwatched() {
        Season s = season(1, ep(1, true), ep(2, false));
        assertFalse(s.allWatched());
    }

    @Test
    void season_allWatched_falseWhenEmpty() {
        Season s = new Season(1);
        assertFalse(s.allWatched());
    }
}
