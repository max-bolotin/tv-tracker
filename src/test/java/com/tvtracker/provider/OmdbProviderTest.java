package com.tvtracker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.Season;
import com.tvtracker.model.TrackedShow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OmdbProviderTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    private OmdbProvider provider(String apiKey) {
        return new OmdbProvider(apiKey, "https://www.omdbapi.com", mapper);
    }

    // ---- isConfigured ----

    @Test
    void isConfigured_returnsFalse_whenApiKeyBlankOrNull() {
        assertThat(provider("").isConfigured()).isFalse();
        assertThat(provider(null).isConfigured()).isFalse();
    }

    @Test
    void isConfigured_returnsTrue_whenApiKeyPresent() {
        assertThat(provider("abc123").isConfigured()).isTrue();
    }

    // ---- enrichShow: guard conditions ----

    @Test
    void enrichShow_doesNothing_whenNotConfigured() {
        TrackedShow show = new TrackedShow();
        show.imdbId = "tt1234567";
        provider("").enrichShow(show);
        assertThat(show.imdbRating).isNull();
        assertThat(show.rtRating).isNull();
    }

    @Test
    void enrichShow_doesNothing_whenImdbIdNull() {
        TrackedShow show = new TrackedShow();
        show.imdbId = null;
        provider("key").enrichShow(show);
        assertThat(show.imdbRating).isNull();
    }

    @Test
    void enrichShow_skips_whenRatingsAreFresh() {
        // 1 month ago = within 6-month window → skip without HTTP call
        TrackedShow show = new TrackedShow();
        show.imdbId = "tt1234567";
        show.ratingsUpdatedAt = LocalDate.now().minusMonths(1).toString();
        provider("key").enrichShow(show); // would throw if HTTP were attempted
        assertThat(show.imdbRating).isNull();
    }

    @Test
    void enrichShow_attemptsHttp_whenRatingsOlderThan6Months_andSwallowsFailure() {
        // 7 months ago → not fresh → HTTP attempted, fails gracefully (no real server)
        TrackedShow show = new TrackedShow();
        show.imdbId = "tt1234567";
        show.ratingsUpdatedAt = LocalDate.now().minusMonths(7).toString();
        provider("key").enrichShow(show); // must not throw
        assertThat(show.imdbRating).isNull(); // HTTP failed, ratings stay null
    }

    @Test
    void enrichShow_attemptsHttp_whenRatingsUpdatedAtIsNull_andSwallowsFailure() {
        TrackedShow show = new TrackedShow();
        show.imdbId = "tt1234567";
        show.ratingsUpdatedAt = null;
        provider("key").enrichShow(show); // must not throw
        assertThat(show.imdbRating).isNull();
    }

    // ---- enrichSeason: guard conditions ----

    @Test
    void enrichSeason_doesNothing_whenNotConfigured() {
        TrackedShow show = new TrackedShow();
        show.imdbId = "tt1234567";
        Season season = new Season(1);
        provider("").enrichSeason(show, season);
        assertThat(season.imdbRating).isNull();
    }

    @Test
    void enrichSeason_doesNothing_whenImdbIdNull() {
        TrackedShow show = new TrackedShow();
        show.imdbId = null;
        Season season = new Season(1);
        provider("key").enrichSeason(show, season);
        assertThat(season.imdbRating).isNull();
    }

    @Test
    void enrichSeason_skips_whenRatingsAreFresh() {
        TrackedShow show = new TrackedShow();
        show.imdbId = "tt1234567";
        Season season = new Season(1);
        season.ratingsUpdatedAt = LocalDate.now().minusWeeks(1).toString();
        provider("key").enrichSeason(show, season); // must not throw
        assertThat(season.imdbRating).isNull();
    }

    @Test
    void enrichSeason_attemptsHttp_whenRatingsStale_andSwallowsFailure() {
        TrackedShow show = new TrackedShow();
        show.imdbId = "tt1234567";
        Season season = new Season(1);
        season.ratingsUpdatedAt = LocalDate.now().minusMonths(7).toString();
        provider("key").enrichSeason(show, season); // must not throw
        assertThat(season.imdbRating).isNull();
    }
}
