package com.tvtracker.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.storage.JsonStorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private final JsonStorageService storage;
    private final ObjectMapper mapper;

    public DataController(JsonStorageService storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() throws Exception {
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(storage.loadAll());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tv-tracker-export.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> importData(@RequestParam("file") MultipartFile file) throws Exception {
        List<TrackedShow> imported = mapper.readValue(file.getBytes(), new TypeReference<>() {});
        // Ensure all shows have IDs, then recalculate tiers
        imported.forEach(show -> {
            if (show.id == null) show.id = UUID.randomUUID().toString();
            show.recalculateStatus();
        });
        storage.saveAll(imported);
        return ResponseEntity.ok("Imported " + imported.size() + " shows.");
    }
}
