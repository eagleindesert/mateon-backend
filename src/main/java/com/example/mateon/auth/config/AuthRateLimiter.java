package com.example.mateon.auth.config;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 고정 창 카운터. 앱 컨테이너 1대 전제. 창이 끝나면 카운트를 처음부터 센다.
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimiter {

    private final AuthRateLimitProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, int limit) {
        long now = System.currentTimeMillis();
        long windowMs = properties.getWindow().toMillis();
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expiresAtMillis <= now) {
                return new Window(1, now + windowMs);
            }
            existing.count++;
            return existing;
        });
        evictExpired(now);
        return window.count <= limit;
    }

    public void checkLoginEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return;
        }
        acquire("login-email:" + email.trim().toLowerCase(), properties.getLoginEmailPerWindow());
    }

    public void checkResetEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return;
        }
        acquire("reset-email:" + email.trim().toLowerCase(), properties.getResetEmailPerWindow());
    }

    private void acquire(String key, int limit) {
        if (!tryAcquire(key, limit)) {
            throw new MateonException(ErrorCode.AUTH_RATE_LIMITED);
        }
    }

    private void evictExpired(long now) {
        if (windows.size() < 1024) {
            return;
        }
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis <= now);
    }

    private static final class Window {
        private int count;
        private final long expiresAtMillis;

        private Window(int count, long expiresAtMillis) {
            this.count = count;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
