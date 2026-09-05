package com.tvtracker.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/events")
public class SseController {

    private static final Logger log = LoggerFactory.getLogger(SseController.class);
    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        return emitter;
    }

    public void broadcast() {
        for (SseEmitter client : clients) {
            try {
                client.send(SseEmitter.event().name("data-changed").data(""));
            } catch (Exception e) {
                clients.remove(client);
                // dead connection — browser navigated away, not an error
            }
        }
        log.info("SSE broadcast sent to {} clients", clients.size());
    }
}
