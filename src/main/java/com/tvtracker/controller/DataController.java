package com.tvtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.ImportExportPayload;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.scheduler.DailyUpdateScheduler;
import com.tvtracker.security.CurrentUserContext;
import com.tvtracker.storage.ImportService;
import com.tvtracker.storage.JsonStorageService;
import com.tvtracker.storage.DataImportService;
import com.tvtracker.storage.MovieStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private static final Logger log = LoggerFactory.getLogger(DataController.class);

    private final JsonStorageService storage;
    private final ImportService importService;
    private final DataImportService dataImportService;
    private final MovieStorageService movieStorage;
    private final ObjectMapper mapper;
    private final DailyUpdateScheduler scheduler;

    public DataController(JsonStorageService storage, ImportService importService,
                          DataImportService dataImportService, MovieStorageService movieStorage,
                          ObjectMapper mapper, DailyUpdateScheduler scheduler) {
        this.storage = storage;
        this.importService = importService;
        this.dataImportService = dataImportService;
        this.movieStorage = movieStorage;
        this.mapper = mapper;
        this.scheduler = scheduler;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() throws Exception {
        String userId = CurrentUserContext.currentUserId();
        List<TrackedShow> shows = storage.loadAll(userId);
        ImportExportPayload payload = importService.toPayload(shows);
        payload.version = 2;
        payload.movies = movieStorage.load(userId);
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tv-tracker-export.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importData(@RequestParam("file") MultipartFile file) throws Exception {
        String userId = CurrentUserContext.currentUserId();
        log.debug("Import started, file size: {} bytes", file.getSize());

        // Stream the multipart to avoid loading entire file into memory
        try (var in = file.getInputStream()) {
            DataImportService.ImportSummary result = dataImportService.importData(in, userId);
            log.debug("Import complete: {} shows and {} movies saved, stubs: {}",
                    result.showsImported(), result.moviesImported(), result.failedTitles());
            return ResponseEntity.ok(new ImportResult(
                    result.showsImported(), result.moviesImported(), result.failedTitles()));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh() {
        String userId = CurrentUserContext.currentUserId();
        scheduler.doCheck(userId);
        return ResponseEntity.ok(Map.of("message", "Refresh complete."));
    }
}
