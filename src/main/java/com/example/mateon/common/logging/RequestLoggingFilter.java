package com.example.mateon.common.logging;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 모든 HTTP 요청을 한 줄로 기록한다. 본문·쿼리·인증 헤더는 기록하지 않는다.
 * ForwardedHeaderFilter 와 SecurityFilterChain 보다 먼저 실행한다.
 */
@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    // SecurityContext 는 보안 필터 종료 시 지워지므로 검증된 사용자 ID 만 요청에 남긴다.
    public static final String USER_ID_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
        RequestLog requestLog = new RequestLog(request, response);
        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public AsyncContext startAsync() {
                return requestLog.track(super.startAsync());
            }

            @Override
            public AsyncContext startAsync(ServletRequest asyncRequest, ServletResponse asyncResponse) {
                return requestLog.track(super.startAsync(asyncRequest, asyncResponse));
            }
        };

        boolean completed = false;
        try {
            filterChain.doFilter(wrapped, response);
            completed = true;
        } finally {
            if (!requestLog.asyncStarted) {
                // 처리되지 않은 예외는 아직 컨테이너가 500 을 설정하기 전이다.
                requestLog.write(completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    private static String clientIp(HttpServletRequest request) {
        // 호스트 Nginx 가 신뢰할 수 있는 값으로 덮어쓴 헤더를 사용한다.
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String first = forwardedFor.split(",", 2)[0].trim();
            if (usableAddress(first)) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        return usableAddress(realIp) ? realIp.trim() : request.getRemoteAddr();
    }

    private static boolean usableAddress(String address) {
        return StringUtils.hasText(address) && !"unknown".equalsIgnoreCase(address.trim());
    }

    private static String singleLine(String value) {
        return value.replaceAll("\\p{Cntrl}", "_");
    }

    private static final class RequestLog implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final long startedAt = System.nanoTime();
        private final String method;
        private final String uri;
        private final String ip;
        private final AtomicBoolean written = new AtomicBoolean();
        private boolean asyncStarted;

        private RequestLog(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
            this.method = singleLine(request.getMethod());
            this.uri = singleLine(request.getRequestURI());
            this.ip = singleLine(clientIp(request));
        }

        private AsyncContext track(AsyncContext context) {
            asyncStarted = true;
            // startAsync 가 반환되기 전에 등록해 즉시 완료되는 요청도 놓치지 않는다.
            context.addListener(this);
            return context;
        }

        private void write(int status) {
            if (written.compareAndSet(false, true)) {
                Object userId = request.getAttribute(USER_ID_ATTRIBUTE);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                log.info("HTTP_REQUEST method={} uri={} status={} ip={} userId={} elapsedMs={}",
                  method, uri, status, ip, userId == null ? "-" : singleLine(userId.toString()), elapsedMs);
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            write(response.getStatus());
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // 타임아웃 처리 후 결정된 최종 상태를 onComplete 에서 기록한다.
        }

        @Override
        public void onError(AsyncEvent event) {
            // 오류 처리 후 결정된 최종 상태를 onComplete 에서 기록한다.
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
