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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

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
    private final Clock clock;
    private final Duration fullRefreshCooldown;
    private final Map<String, Instant> lastCompletedRefresh = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> refreshInProgress = new ConcurrentHashMap<>();

    public DataController(JsonStorageService storage, ImportService importService,
                          DataImportService dataImportService, MovieStorageService movieStorage,
                          ObjectMapper mapper, DailyUpdateScheduler scheduler) {
        this(storage, importService, dataImportService, movieStorage, mapper, scheduler,
                Clock.systemUTC(), Duration.ofHours(6));
    }

    @Autowired
    public DataController(JsonStorageService storage, ImportService importService,
                          DataImportService dataImportService, MovieStorageService movieStorage,
                          ObjectMapper mapper, DailyUpdateScheduler scheduler, Clock clock,
                          @Value("${app.cache.full-refresh-cooldown:PT6H}") Duration fullRefreshCooldown) {
        this.storage = storage;
        this.importService = importService;
        this.dataImportService = dataImportService;
        this.movieStorage = movieStorage;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.clock = clock;
        this.fullRefreshCooldown = fullRefreshCooldown;
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
    public ResponseEntity<RefreshResponse> refresh() {
        String userId = CurrentUserContext.currentUserId();
        AtomicBoolean active = refreshInProgress.computeIfAbsent(userId, ignored -> new AtomicBoolean());
        if (!active.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new RefreshResponse("A refresh is already running.", null));
        }
        try {
            Instant now = clock.instant();
            Instant lastRefresh = lastCompletedRefresh.get(userId);
            if (lastRefresh != null) {
                Instant nextRefreshAt = lastRefresh.plus(fullRefreshCooldown);
                if (now.isBefore(nextRefreshAt)) {
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .body(new RefreshResponse("Refresh cooldown is active.", nextRefreshAt));
                }
            }
            DailyUpdateScheduler.RefreshResult result = scheduler.doCheckWithResult(userId);
            if (!result.completed()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(new RefreshResponse("Refresh failed; existing data was kept.", null));
            }
            Instant nextRefreshAt = clock.instant().plus(fullRefreshCooldown);
            lastCompletedRefresh.put(userId, clock.instant());
            String message = result.partialFailure()
                    ? "Refresh complete with partial failures."
                    : "Refresh complete.";
            return ResponseEntity.ok(new RefreshResponse(message, nextRefreshAt));
        } catch (RuntimeException e) {
            log.error("Manual refresh failed for user {}", userId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new RefreshResponse("Refresh failed; existing data was kept.", null));
        } finally {
            active.set(false);
        }
    }

    public record RefreshResponse(String message, Instant nextRefreshAt) {}
}
