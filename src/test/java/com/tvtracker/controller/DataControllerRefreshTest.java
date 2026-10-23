package com.tvtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.scheduler.DailyUpdateScheduler;
import com.tvtracker.security.CurrentUserContext;
import com.tvtracker.storage.DataImportService;
import com.tvtracker.storage.ImportService;
import com.tvtracker.storage.JsonStorageService;
import com.tvtracker.storage.MovieStorageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataControllerRefreshTest {
    private static final String USER_ID = "refresh-user";
    private DailyUpdateScheduler scheduler;
    private DataController controller;
    private MockedStatic<CurrentUserContext> userContext;

    @BeforeEach
    void setUp() {
        scheduler = mock(DailyUpdateScheduler.class);
        controller = new DataController(
                mock(JsonStorageService.class),
                mock(ImportService.class),
                mock(DataImportService.class),
                mock(MovieStorageService.class),
                new ObjectMapper(),
                scheduler,
                Clock.fixed(Instant.parse("2026-10-23T11:00:00Z"), ZoneOffset.UTC),
                Duration.ofHours(6));
        userContext = mockStatic(CurrentUserContext.class);
        userContext.when(CurrentUserContext::currentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        userContext.close();
    }

    @Test
    void successfulRefreshEnforcesPerUserCooldown() {
        when(scheduler.doCheckWithResult(USER_ID))
                .thenReturn(new DailyUpdateScheduler.RefreshResult(true, false));

        var first = controller.refresh();
        var second = controller.refresh();

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().nextRefreshAt())
                .isEqualTo(Instant.parse("2026-10-23T17:00:00Z"));
        assertThat(second.getStatusCode().value()).isEqualTo(429);
        assertThat(second.getBody().nextRefreshAt())
                .isEqualTo(Instant.parse("2026-10-23T17:00:00Z"));
        verify(scheduler).doCheckWithResult(USER_ID);
    }

    @Test
    void failedRefreshDoesNotStartCooldown() {
        when(scheduler.doCheckWithResult(USER_ID)).thenReturn(
                new DailyUpdateScheduler.RefreshResult(false, false),
                new DailyUpdateScheduler.RefreshResult(true, false));

        var failed = controller.refresh();
        var retried = controller.refresh();

        assertThat(failed.getStatusCode().value()).isEqualTo(502);
        assertThat(retried.getStatusCode().value()).isEqualTo(200);
        verify(scheduler, org.mockito.Mockito.times(2)).doCheckWithResult(USER_ID);
    }

    @Test
    void oneShowFailureStartsCooldownAndIsReportedAsPartial() throws Exception {
        JsonStorageService storage = mock(JsonStorageService.class);
        MetadataService metadata = mock(MetadataService.class);
        DailyUpdateScheduler actualScheduler = new DailyUpdateScheduler(storage, metadata, 7, 21, 0L);
        TrackedShow show = new TrackedShow();
        show.tmdbId = 42L;
        show.title = "Unavailable show";
        when(storage.loadAll(USER_ID)).thenReturn(new ArrayList<>(List.of(show)));
        when(metadata.fetchRecentlyUpdatedTmdbIds()).thenReturn(List.of());
        when(metadata.fetchRecentlyUpdatedTvmazeIds()).thenReturn(List.of());
        when(metadata.fetchDetails(42L, null)).thenThrow(new RuntimeException("provider unavailable"));
        controller = new DataController(
                storage,
                mock(ImportService.class),
                mock(DataImportService.class),
                mock(MovieStorageService.class),
                new ObjectMapper(),
                actualScheduler,
                Clock.fixed(Instant.parse("2026-10-23T11:00:00Z"), ZoneOffset.UTC),
                Duration.ofHours(6));

        var first = controller.refresh();
        var second = controller.refresh();

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().message()).contains("partial failures");
        assertThat(first.getBody().nextRefreshAt())
                .isEqualTo(Instant.parse("2026-10-23T17:00:00Z"));
        assertThat(second.getStatusCode().value()).isEqualTo(429);
    }
}
