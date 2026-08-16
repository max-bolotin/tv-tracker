package com.tvtracker.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.controller.SseController;
import com.tvtracker.model.TrackedShow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class JsonStorageService {

    private final File storageRoot;
    private final File defaultFile;
    private final ObjectMapper mapper;
    private final SseController sse;
    private final boolean legacySingleFileMode;

    public JsonStorageService(@Value("${app.storage.path:./data/users/}") String storagePath,
                              ObjectMapper mapper, SseController sse) throws IOException {
        this.mapper = mapper;
        this.sse = sse;
        this.legacySingleFileMode = storagePath.toLowerCase(Locale.ROOT).endsWith(".json");

        if (legacySingleFileMode) {
            this.defaultFile = new File(storagePath);
            this.storageRoot = this.defaultFile.getParentFile() != null ? this.defaultFile.getParentFile() : new File(".");
        } else {
            this.storageRoot = new File(storagePath);
            this.defaultFile = new File(this.storageRoot, "default.json");
        }

        if (!this.storageRoot.exists() && !this.storageRoot.mkdirs()) {
            throw new IOException("Failed to create storage directory: " + this.storageRoot.getAbsolutePath());
        }
        ensureDefaultUserFile();
    }

    public synchronized List<TrackedShow> loadAll() throws IOException {
        return loadAll("default");
    }

    public synchronized List<TrackedShow> loadAll(String userId) throws IOException {
        File file = resolveUserFile(userId);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        return mapper.readValue(file, new TypeReference<>() {});
    }

    public synchronized void saveAll(List<TrackedShow> shows) throws IOException {
        saveAll("default", shows);
    }

    public synchronized void saveAll(String userId, List<TrackedShow> shows) throws IOException {
        File file = resolveUserFile(userId);
        ensureParentDirectory(file);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, shows);
        sse.broadcast();
    }

    public synchronized Optional<TrackedShow> findById(String id) throws IOException {
        return findById("default", id);
    }

    public synchronized Optional<TrackedShow> findById(String userId, String id) throws IOException {
        return loadAll(userId).stream().filter(s -> s.id.equals(id)).findFirst();
    }

    public synchronized TrackedShow save(TrackedShow show) throws IOException {
        return save("default", show);
    }

    public synchronized TrackedShow save(String userId, TrackedShow show) throws IOException {
        List<TrackedShow> shows = loadAll(userId);
        if (show.id == null) show.id = UUID.randomUUID().toString();
        shows.removeIf(s -> s.id.equals(show.id));
        shows.add(show);
        saveAll(userId, shows);
        return show;
    }

    public synchronized void reorder(List<String> orderedIds) throws IOException {
        reorder("default", orderedIds);
    }

    public synchronized void reorder(String userId, List<String> orderedIds) throws IOException {
        List<TrackedShow> shows = loadAll(userId);
        Map<String, TrackedShow> byId = new LinkedHashMap<>();
        shows.forEach(s -> byId.put(s.id, s));
        List<TrackedShow> reordered = new ArrayList<>(orderedIds.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .toList());
        shows.stream().filter(s -> !orderedIds.contains(s.id)).forEach(reordered::add);
        saveAll(userId, reordered);
    }

    public synchronized boolean delete(String id) throws IOException {
        return delete("default", id);
    }

    public synchronized boolean delete(String userId, String id) throws IOException {
        List<TrackedShow> shows = loadAll(userId);
        boolean removed = shows.removeIf(s -> s.id.equals(id));
        if (removed) saveAll(userId, shows);
        return removed;
    }

    private File resolveUserFile(String userId) {
        if (legacySingleFileMode) {
            return defaultFile;
        }
        String normalizedUserId = userId == null || userId.isBlank() ? "default" : userId.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(storageRoot, normalizedUserId + ".json");
    }

    private void ensureDefaultUserFile() throws IOException {
        File file = resolveUserFile("default");
        ensureParentDirectory(file);
        if (!file.exists()) {
            mapper.writeValue(file, new ArrayList<>());
        }
    }

    private void ensureParentDirectory(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create storage directory: " + parent.getAbsolutePath());
        }
    }
}
