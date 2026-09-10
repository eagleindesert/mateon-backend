package com.example.mateon.notification.repository;

import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 유저당 SSE 연결을 여러 개 보관한다. 앱과 웹 탭이 동시에 구독해도 마지막 연결만
 * 남는 일이 없게 한다.
 */
@Repository
public class EmitterRepository {

    private final Map<Long, ConcurrentHashMap<String, SseEmitter>> emitters = new ConcurrentHashMap<>();

    public String save(Long userId, SseEmitter emitter) {
        String connectionId = UUID.randomUUID().toString();
        save(userId, connectionId, emitter);
        return connectionId;
    }

    public void save(Long userId, String connectionId, SseEmitter emitter) {
        emitters.computeIfAbsent(userId, id -> new ConcurrentHashMap<>()).put(connectionId, emitter);
    }

    public void delete(Long userId, String connectionId) {
        ConcurrentHashMap<String, SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null) {
            return;
        }
        userEmitters.remove(connectionId);
        if (userEmitters.isEmpty()) {
            emitters.remove(userId, userEmitters);
        }
    }

    public void deleteEmitter(Long userId, SseEmitter emitter) {
        ConcurrentHashMap<String, SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null) {
            return;
        }
        userEmitters.entrySet().removeIf(entry -> entry.getValue() == emitter);
        if (userEmitters.isEmpty()) {
            emitters.remove(userId, userEmitters);
        }
    }

    public void deleteAll(Long userId) {
        emitters.remove(userId);
    }

    public Collection<SseEmitter> getAll(Long userId) {
        ConcurrentHashMap<String, SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return List.of();
        }
        return List.copyOf(userEmitters.values());
    }
}
