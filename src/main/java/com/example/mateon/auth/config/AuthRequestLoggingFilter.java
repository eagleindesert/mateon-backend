package com.example.mateon.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 인증 요청이 끝나면 IP·경로·응답 상태·처리 시간을 한 줄로 기록한다.
 * 본문, 쿼리 문자열, 인증 헤더는 읽거나 기록하지 않는다.
 */
@Slf4j
@Component
public class AuthRequestLoggingFilter extends OncePerRequestFilter {

    private static final Set<String> LOGGED_PATHS = Set.of(
      "/api/auth/email/request",
      "/api/auth/email/verify",
      "/api/auth/school/email/request",
      "/api/auth/school/email/verify",
      "/api/auth/signup",
      "/api/auth/login",
      "/api/auth/social/kakao",
      "/api/auth/social/kakao/code",
      "/api/auth/token/refresh",
      "/api/auth/password/reset/request",
      "/api/auth/password/reset/confirm",
      "/api/auth/password/change",
      "/api/auth/logout"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
          || !LOGGED_PATHS.contains(apiPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
        String path = apiPath(request);
        String ip = request.getRemoteAddr();
        long startedAt = System.nanoTime();
        boolean completed = false;
        try {
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            // 컨테이너까지 전파되는 예외는 아직 response 에 오류 상태가 설정되지 않았을 수 있다.
            int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("AUTH_REQUEST method={} path={} ip={} status={} durationMs={}",
              request.getMethod(), path, ip, status, durationMs);
        }
    }

    private static String apiPath(HttpServletRequest request) {
        // ForwardedHeaderFilter 가 반영한 프록시 prefix 와 실제 context path 를 모두 제외한다.
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
