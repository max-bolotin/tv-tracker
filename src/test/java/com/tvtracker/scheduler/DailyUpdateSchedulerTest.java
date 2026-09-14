package com.tvtracker.scheduler;

import com.tvtracker.model.Actor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DailyUpdateSchedulerTest {

    @Mock
    JsonStorageService storage;

    @Mock
    MetadataService metadata;

    DailyUpdateScheduler scheduler;

    @BeforeEach
    void setup() {
        scheduler = new DailyUpdateScheduler(storage, metadata, 7, 21, 1000L);
    }

    private TrackedShow makeShow(String title, List<Actor> cast) {
        TrackedShow s = new TrackedShow();
        s.title = title;
        s.cast = cast;
        return s;
    }

    @Test
    void freshCastNull_keepsExistingCast() throws Exception {
        TrackedShow existing = makeShow("E1", new ArrayList<>(List.of(new Actor("A", null))));
        TrackedShow fresh = makeShow("E1", null);

        when(storage.listUserIds()).thenReturn(List.of("u1"));
        when(storage.loadAll("u1")).thenReturn(new ArrayList<>(List.of(existing)));
        when(metadata.fetchDetails(existing.tmdbId, existing.tvmazeId, false)).thenReturn(fresh);

        scheduler.doCheck();

        assertThat(existing.cast).isNotNull().hasSize(1);
        verify(storage).saveAll(eq("u1"), anyList());
    }

    @Test
    void freshEmpty_existingNonEmpty_keepsExisting() throws Exception {
        TrackedShow existing = makeShow("E2", new ArrayList<>(List.of(new Actor("B", null))));
        TrackedShow fresh = makeShow("E2", new ArrayList<>());

        when(storage.listUserIds()).thenReturn(List.of("u2"));
        when(storage.loadAll("u2")).thenReturn(new ArrayList<>(List.of(existing)));
        when(metadata.fetchDetails(existing.tmdbId, existing.tvmazeId, false)).thenReturn(fresh);

        scheduler.doCheck();

        assertThat(existing.cast).hasSize(1);
        verify(storage).saveAll(eq("u2"), anyList());
    }

    @Test
    void freshEmpty_existingNull_assignsEmpty() throws Exception {
        TrackedShow existing = makeShow("E3", null);
        TrackedShow fresh = makeShow("E3", new ArrayList<>());

        when(storage.listUserIds()).thenReturn(List.of("u3"));
        when(storage.loadAll("u3")).thenReturn(new ArrayList<>(List.of(existing)));
        when(metadata.fetchDetails(existing.tmdbId, existing.tvmazeId, false)).thenReturn(fresh);

        scheduler.doCheck();

        assertThat(existing.cast).isNotNull().isEmpty();
        verify(storage).saveAll(eq("u3"), anyList());
    }

    @Test
    void freshNonEmpty_replacesExisting() throws Exception {
        TrackedShow existing = makeShow("E4", new ArrayList<>(List.of(new Actor("C", null))));
        TrackedShow fresh = makeShow("E4", new ArrayList<>(List.of(new Actor("D", null))));

        when(storage.listUserIds()).thenReturn(List.of("u4"));
        when(storage.loadAll("u4")).thenReturn(new ArrayList<>(List.of(existing)));
        when(metadata.fetchDetails(existing.tmdbId, existing.tvmazeId, false)).thenReturn(fresh);

        scheduler.doCheck();

        assertThat(existing.cast).hasSize(1);
        assertThat(existing.cast.getFirst().name).isEqualTo("D");
        verify(storage).saveAll(eq("u4"), anyList());
    }
}
