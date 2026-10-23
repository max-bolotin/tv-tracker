package com.tvtracker.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.controller.SseController;
import com.tvtracker.model.MediaType;
import com.tvtracker.model.TrackedShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStorageMediaTypeMigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsLegacyUserFileAsTvWithoutRewritingIt() throws Exception {
        Path userFile = tempDir.resolve("default.json");
        String legacyJson = """
                [
                  {
                    "id": "legacy-show",
                    "tmdbId": 42,
                    "title": "Legacy show",
                    "watchStatus": "NOT_WATCHED",
                    "seasons": []
                  }
                ]
                """;
        Files.writeString(userFile, legacyJson);
        ObjectMapper springConfiguredMapper = Jackson2ObjectMapperBuilder.json().build();
        JsonStorageService storage = new JsonStorageService(
                userFile.toString(), springConfiguredMapper, new SseController());

        TrackedShow loaded = storage.loadAll().getFirst();

        assertEquals(MediaType.TV, loaded.mediaType);
        assertEquals(legacyJson, Files.readString(userFile));
    }

    @Test
    void springConfiguredMapperIgnoresNewMediaTypeWhenReadingLegacyShape() throws Exception {
        ObjectMapper springConfiguredMapper = Jackson2ObjectMapperBuilder.json().build();
        TrackedShow currentShow = new TrackedShow();
        currentShow.tmdbId = 42L;
        currentShow.title = "Current show";
        String currentJson = springConfiguredMapper.writeValueAsString(currentShow);

        LegacyTrackedShow restored = springConfiguredMapper.readValue(currentJson, LegacyTrackedShow.class);

        assertEquals(42L, restored.tmdbId);
        assertEquals("Current show", restored.title);
        assertFalse(springConfiguredMapper.isEnabled(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertTrue(currentJson.contains("\"mediaType\":\"tv\""));
    }

    private static class LegacyTrackedShow {
        public Long tmdbId;
        public String title;
    }
}
