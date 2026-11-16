package com.example.mateon.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mateon.auth.config.AuthRateLimitFilter;
import com.example.mateon.auth.config.AuthRateLimitProperties;
import com.example.mateon.auth.config.AuthRateLimiter;
import com.example.mateon.auth.jwt.JwtAuthenticationFilter;
import com.example.mateon.auth.jwt.JwtTokenProvider;
import com.example.mateon.common.config.CorsProperties;
import com.example.mateon.common.config.RequestLoggingConfig;
import com.example.mateon.common.config.SecurityConfig;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestLoggingFilterTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "server.forward-headers-strategy=framework");
        context.register(SecurityConfig.class, RequestLoggingConfig.class, RequestLoggingFilter.class, Dependencies.class);
        context.refresh();
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .addFilters(context.getBean(RequestLoggingFilter.class),
            context.getBean("forwardedHeadersRegistration", FilterRegistrationBean.class).getFilter())
          .apply(springSecurity(context.getBean("springSecurityFilterChain", Filter.class)))
          .build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    @Test
    @DisplayName("프록시 IP 와 prefix 를 반영하고, 본문·쿼리·인증 헤더 없이 한 줄만 기록한다")
    void forwardedRequestLogsOnlyMetadataOnce() throws Exception {
        mockMvc.perform(post("/api/auth/login?token=query-secret")
          .header("X-Forwarded-For", "203.0.113.10, 10.0.0.1")
          .header("X-Real-IP", "203.0.113.20")
          .header("Forwarded", "for=203.0.113.30;proto=https")
          .header("X-Forwarded-Proto", "https")
          .header("X-Forwarded-Prefix", "/proxy-prefix")
          .header("Authorization", "Bearer header-secret")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"password\":\"body-secret\",\"code\":\"code-secret\"}"))
          .andExpect(status().isOk());

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(logs.list.getFirst().getFormattedMessage())
          .matches("HTTP_REQUEST method=POST uri=/api/auth/login status=200 ip=203\\.0\\.113\\.10 userId=- elapsedMs=\\d+")
          .doesNotContain("secret", "proxy-prefix");
        assertThat(context.getBean("requestLoggingFilterRegistration", FilterRegistrationBean.class).getOrder())
          .isLessThan(context.getBean("forwardedHeadersRegistration", FilterRegistrationBean.class).getOrder());
    }

    @Test
    @DisplayName("로그인 실패와 학교 인증의 보안 필터 거절 상태를 기록한다")
    void authenticationFailuresAreLogged() throws Exception {
        mockMvc.perform(post("/api/auth/login").content("bad-credentials"))
          .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/school/email/request"))
          .andExpect(status().isForbidden());

        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(0).getFormattedMessage())
          .contains("uri=/api/auth/login", "status=401", "userId=-");
        assertThat(logs.list.get(1).getFormattedMessage())
          .contains("uri=/api/auth/school/email/request", "status=403");
    }

    @Test
    @DisplayName("요청 제한 필터에서 중단된 로그인도 429 로 기록한다")
    void rateLimitRejectionIsLogged() throws Exception {
        mockMvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")).andExpect(status().isTooManyRequests());

        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(1).getFormattedMessage())
          .contains("uri=/api/auth/login", "status=429");
    }

    @Test
    @DisplayName("헬스체크, CORS preflight, 다른 인증 경로도 각각 기록한다")
    void allPathsAndPreflightAreLogged() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(options("/api/auth/login")
          .header("Origin", "http://localhost:3000")
          .header("Access-Control-Request-Method", "POST"))
          .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/test-only")).andExpect(status().isOk());

        assertThat(logs.list).hasSize(3);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("method=GET", "uri=/health");
        assertThat(logs.list.get(1).getFormattedMessage()).contains("method=OPTIONS", "status=200");
        assertThat(logs.list.get(2).getFormattedMessage()).contains("uri=/api/auth/test-only");
    }

    @Test
    @DisplayName("필터 밖으로 전파되는 예외를 200 으로 기록하지 않고 그대로 전달한다")
    void unhandledExceptionIsLoggedAs500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("exception-secret");

        assertThatThrownBy(() -> context.getBean(RequestLoggingFilter.class)
          .doFilter(request, response, (req, res) -> { throw failure; }))
          .isSameAs(failure);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
          .contains("status=500")
          .doesNotContain("exception-secret");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    @DisplayName("모든 HTTP 메서드에 인증된 userId 와 최종 상태가 남는다")
    void everyMethodLogsAuthenticatedUser(String method) throws Exception {
        int expectedStatus = "OPTIONS".equals(method) ? 200 : 204;
        mockMvc.perform(request(HttpMethod.valueOf(method), "/api/users/me")
          .header("Authorization", "Bearer valid-access"))
          .andExpect(status().is(expectedStatus));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
          .contains("method=" + method, "uri=/api/users/me", "status=" + expectedStatus, "userId=42", "elapsedMs=")
          .doesNotContain("valid-access");
    }

    @Test
    @DisplayName("CORS 에서 먼저 거절한 요청도 기록한다")
    void corsRejectionIsLogged() throws Exception {
        mockMvc.perform(get("/health").header("Origin", "https://untrusted.example"))
          .andExpect(status().isForbidden());

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("method=GET", "status=403");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "unknown"})
    @DisplayName("X-Forwarded-For 가 비었으면 X-Real-IP 를 사용한다")
    void realIpFallback(String forwardedFor) throws Exception {
        mockMvc.perform(get("/health")
          .header("X-Forwarded-For", forwardedFor)
          .header("X-Real-IP", " 2001:db8::1 "));

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("ip=2001:db8::1");
    }

    @Test
    void realIpWithoutForwardedFor() throws Exception {
        mockMvc.perform(get("/health").header("X-Real-IP", "203.0.113.20"))
          .andExpect(status().isOk());
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("ip=203.0.113.20");
    }

    @Test
    void remoteAddressWithoutProxyHeaders() throws Exception {
        mockMvc.perform(get("/health").with(request -> {
            request.setRemoteAddr("192.0.2.8");
            return request;
        })).andExpect(status().isOk());
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("ip=192.0.2.8");
    }

    @Test
    @DisplayName("헤더의 개행이 로그를 여러 줄로 만들지 않는다")
    void logValuesStayOnOneLine() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader("X-Real-IP", "203.0.113.1\r\nforged=entry");
        context.getBean(RequestLoggingFilter.class).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("\r", "\n");
    }

    @Test
    @DisplayName("SSE 등 비동기 요청은 완료 시점의 상태와 userId 를 한 번 기록한다")
    void asyncCompletionLogsOnce() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notifications/subscribe");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        context.getBean(RequestLoggingFilter.class).doFilter(request, response, (req, res) -> {
            req.setAttribute(RequestLoggingFilter.USER_ID_ATTRIBUTE, 42L);
            req.startAsync(req, res);
        });
        assertThat(logs.list).isEmpty();

        MockAsyncContext async = (MockAsyncContext) request.getAsyncContext();
        response.setStatus(503);
        for (AsyncListener listener : async.getListeners()) {
            listener.onTimeout(new AsyncEvent(async));
            listener.onError(new AsyncEvent(async, new IOException("private-error")));
        }
        assertThat(logs.list).isEmpty();
        async.complete();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
          .contains("uri=/api/notifications/subscribe", "status=503", "userId=42")
          .doesNotContain("private-error");
    }

    @Test
    @DisplayName("필터가 반환되기 전에 비동기 요청이 끝나도 한 번만 기록한다")
    void immediateAsyncCompletionIsNotLostOrDuplicated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.setAsyncSupported(true);
        context.getBean(RequestLoggingFilter.class).doFilter(request, new MockHttpServletResponse(),
          (req, res) -> req.startAsync().complete());
        assertThat(logs.list).hasSize(1);
    }

    @Test
    @DisplayName("비동기 처리가 새 주기로 넘어가도 완료 로그를 유지한다")
    void asyncListenerFollowsTheNextCycle() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        context.getBean(RequestLoggingFilter.class).doFilter(request, response, (req, res) -> req.startAsync(req, res));

        MockAsyncContext first = (MockAsyncContext) request.getAsyncContext();
        MockAsyncContext next = new MockAsyncContext(request, response);
        for (AsyncListener listener : first.getListeners()) {
            listener.onStartAsync(new AsyncEvent(next));
        }
        assertThat(next.getListeners()).hasSize(1);
        assertThat(logs.list).isEmpty();
        next.complete();
        next.getListeners().getFirst().onComplete(new AsyncEvent(next));
        assertThat(logs.list).hasSize(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            JwtTokenProvider provider = mock(JwtTokenProvider.class);
            when(provider.validateToken("valid-access", JwtTokenProvider.TokenType.ACCESS)).thenReturn(true);
            when(provider.getUserIdFromToken("valid-access")).thenReturn(42L);
            return new JwtAuthenticationFilter(provider);
        }

        @Bean
        AuthRateLimitFilter authRateLimitFilter() {
            AuthRateLimitProperties properties = new AuthRateLimitProperties();
            properties.setIpPerWindow(1);
            return new AuthRateLimitFilter(new AuthRateLimiter(properties), properties, new ObjectMapper());
        }

        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties();
        }
    }

    @RestController
    static class StubController {
        @PostMapping("/api/auth/login")
        String login(@RequestBody(required = false) String body) {
            if ("bad-credentials".equals(body)) {
                throw new BadCredentialsException("invalid credentials");
            }
            return "ok";
        }

        // 인증 전용 목록에 없던 경로도 기록되어야 한다.
        @PostMapping("/api/auth/test-only")
        String otherAuthApi() {
            return "ok";
        }

        @GetMapping("/health")
        String health() {
            return "up";
        }

        @RequestMapping("/api/users/me")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void user() {
        }
    }
}
