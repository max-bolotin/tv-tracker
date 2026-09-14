package com.tvtracker.scheduler;

import com.tvtracker.model.TrackedShow;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.storage.JsonStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DailyUpdateSchedulerOmdb401Test {

    @Mock
    JsonStorageService storage;

    @Mock
    MetadataService metadata;

    DailyUpdateScheduler scheduler;

    @BeforeEach
    void setup() {
        // fast run: small staleness window and no pause
        scheduler = new DailyUpdateScheduler(storage, metadata, 1, 2, 0L);
    }

    @Test
    void omdb401_haltsFurtherCalls() throws Exception {
        TrackedShow s1 = new TrackedShow(); s1.title = "S1";
        TrackedShow s2 = new TrackedShow(); s2.title = "S2";

        when(storage.listUserIds()).thenReturn(List.of("u1"));
        when(storage.loadAll("u1")).thenReturn(new ArrayList<>(List.of(s1, s2)));
        // fetchDetails used in metadata pass (return any non-null)
        when(metadata.fetchDetails(any(), any(), eq(false))).thenReturn(new TrackedShow());
        when(metadata.isOmdbConfigured()).thenReturn(true);

        AtomicInteger calls = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("HTTP 401 - {\"Response\":\"False\",\"Error\":\"Invalid API key!\"}");
            }
            return null;
        }).when(metadata).enrichRatings(any(TrackedShow.class), eq(true));

        scheduler.doCheck();

        // ensure enrichRatings was called once and halted on 401
        verify(metadata, times(1)).enrichRatings(any(TrackedShow.class), eq(true));
    }
}
