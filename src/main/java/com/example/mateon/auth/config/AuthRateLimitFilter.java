package com.example.mateon.auth.config;

import com.example.mateon.common.dto.BaseResponse;
import com.example.mateon.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 로그인·소셜·비밀번호 찾기 요청을 IP 당 제한한다. 본문의 email 한도는 서비스가 본다.
 *
 * <p>
 * 필터에서 예외를 던지면 {@code @RestControllerAdvice} 가 못 잡으므로 429 JSON 을 여기서 쓴다.
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of(
      "/api/auth/login",
      "/api/auth/social/kakao",
      "/api/auth/social/kakao/code",
      "/api/auth/password/reset/request"
    );

    private final AuthRateLimiter authRateLimiter;
    private final AuthRateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
          || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
        String key = "ip:" + request.getRequestURI() + ":" + request.getRemoteAddr();
        if (!authRateLimiter.tryAcquire(key, properties.getIpPerWindow())) {
            response.setStatus(ErrorCode.AUTH_RATE_LIMITED.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
              BaseResponse.error(ErrorCode.AUTH_RATE_LIMITED.getMessage()));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
