package com.tvtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.ImportExportPayload;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.storage.ImportService;
import com.tvtracker.storage.JsonStorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private final JsonStorageService storage;
    private final ImportService importService;
    private final ObjectMapper mapper;

    public DataController(JsonStorageService storage, ImportService importService, ObjectMapper mapper) {
        this.storage = storage;
        this.importService = importService;
        this.mapper = mapper;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() throws Exception {
        List<TrackedShow> shows = storage.loadAll();
        ImportExportPayload payload = importService.toPayload(shows);
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tv-tracker-export.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> importData(@RequestParam("file") MultipartFile file) throws Exception {
        ImportExportPayload payload = mapper.readValue(file.getBytes(), ImportExportPayload.class);
        List<TrackedShow> resolved = importService.resolve(payload);
        storage.saveAll(resolved);
        return ResponseEntity.ok("Imported " + resolved.size() + " shows.");
    }
}
