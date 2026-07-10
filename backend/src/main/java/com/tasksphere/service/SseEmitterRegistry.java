package com.tasksphere.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds one or more live {@link SseEmitter} connections per logged-in user
 * (a user can have the dashboard open in multiple tabs/devices). Used to
 * push real-time events — new notifications, booking status changes,
 * provider GPS pings — straight to the browser over Server-Sent Events,
 * without the frontend having to poll.
 *
 * SSE was chosen over raw WebSocket because it needs zero extra
 * infrastructure (no STOMP broker, no extra client library — the browser's
 * native EventSource is enough) while still giving genuine server-push
 * real-time delivery, which is all this REST/JWT stack needs.
 */
@Service
public class SseEmitterRegistry {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes per connection; client auto-reconnects

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> connections = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        connections.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("status", "connected")));
        } catch (IOException ignored) { /* client disconnected before we could greet it */ }

        return emitter;
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> list = connections.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) connections.remove(userId);
        }
    }

    /** Push an event to every live connection a user currently has open. */
    public void send(Long userId, String eventName, Object data) {
        List<SseEmitter> list = connections.get(userId);
        if (list == null || list.isEmpty()) return; // user has no live tab open — they'll see it via REST poll/next login

        for (SseEmitter emitter : List.copyOf(list)) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                remove(userId, emitter);
            }
        }
    }

    public int liveConnectionCount() {
        return connections.values().stream().mapToInt(List::size).sum();
    }
}
