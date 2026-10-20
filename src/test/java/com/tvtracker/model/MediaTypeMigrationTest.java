package com.tvtracker.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaTypeMigrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyShowWithoutMediaTypeDefaultsToTv() throws Exception {
        TrackedShow show = mapper.readValue("""
                {"tmdbId": 42, "title": "Legacy show"}
                """, TrackedShow.class);

        assertEquals(MediaType.TV, show.mediaType);
    }

    @Test
    void nullMediaTypeAlsoDefaultsToTv() throws Exception {
        TrackedShow show = mapper.readValue("""
                {"tmdbId": 42, "mediaType": null, "title": "Legacy show"}
                """, TrackedShow.class);

        assertEquals(MediaType.TV, show.mediaType);
    }

    @Test
    void blankMediaTypeDefaultsToTvButUnknownMediaTypeFails() throws Exception {
        TrackedShow show = mapper.readValue("""
                {"tmdbId": 42, "mediaType": "   ", "title": "Legacy show"}
                """, TrackedShow.class);

        assertEquals(MediaType.TV, show.mediaType);
        assertThrows(IllegalArgumentException.class, () -> MediaType.fromJson("anime"));
    }

    @Test
    void mediaTypeIsSerializedInLowercaseAndMovieEntryHasMovieDefaults() throws Exception {
        MovieEntry movie = new MovieEntry();
        movie.tmdbId = 42L;
        movie.title = "Example movie";

        String json = mapper.writeValueAsString(movie);
        MovieEntry restored = mapper.readValue(json, MovieEntry.class);

        assertEquals(MediaType.MOVIE, restored.mediaType);
        assertEquals(MovieStatus.PLANNED, restored.status);
        assertEquals("movie", mapper.readTree(json).get("mediaType").asText());
        assertNull(restored.rottenTomatoesScore);
        assertNull(restored.watchedDate);
    }
}
