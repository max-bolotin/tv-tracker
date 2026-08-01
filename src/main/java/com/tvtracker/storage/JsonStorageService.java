package com.tvtracker.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.model.TrackedShow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class JsonStorageService {

    private final File dataFile;
    private final ObjectMapper mapper;

    public JsonStorageService(@Value("${app.storage.path}") String storagePath, ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        this.dataFile = new File(storagePath);
        dataFile.getParentFile().mkdirs();
        if (!dataFile.exists()) {
            mapper.writeValue(dataFile, new ArrayList<>());
        }
    }

    public synchronized List<TrackedShow> loadAll() throws IOException {
        return mapper.readValue(dataFile, new TypeReference<>() {});
    }

    public synchronized void saveAll(List<TrackedShow> shows) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(dataFile, shows);
    }

    public synchronized Optional<TrackedShow> findById(String id) throws IOException {
        return loadAll().stream().filter(s -> s.id.equals(id)).findFirst();
    }

    public synchronized TrackedShow save(TrackedShow show) throws IOException {
        List<TrackedShow> shows = loadAll();
        if (show.id == null) show.id = UUID.randomUUID().toString();
        shows.removeIf(s -> s.id.equals(show.id));
        shows.add(show);
        saveAll(shows);
        return show;
    }

    public synchronized boolean delete(String id) throws IOException {
        List<TrackedShow> shows = loadAll();
        boolean removed = shows.removeIf(s -> s.id.equals(id));
        if (removed) saveAll(shows);
        return removed;
    }
}
