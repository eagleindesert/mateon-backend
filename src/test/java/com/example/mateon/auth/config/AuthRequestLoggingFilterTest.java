package com.example.mateon.auth.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mateon.auth.jwt.JwtAuthenticationFilter;
import com.example.mateon.auth.jwt.JwtTokenProvider;
import com.example.mateon.common.config.CorsProperties;
import com.example.mateon.common.config.SecurityConfig;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthRequestLoggingFilterTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(AuthRequestLoggingFilter.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SecurityConfig.class, AuthRequestLoggingFilter.class, Dependencies.class);
        context.refresh();
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .addFilters(new ForwardedHeaderFilter())
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
          .header("X-Forwarded-For", "203.0.113.10")
          .header("X-Forwarded-Proto", "https")
          .header("X-Forwarded-Prefix", "/proxy-prefix")
          .header("Authorization", "Bearer header-secret")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"password\":\"body-secret\",\"code\":\"code-secret\"}"))
          .andExpect(status().isOk());

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(logs.list.getFirst().getFormattedMessage())
          .matches("AUTH_REQUEST method=POST path=/api/auth/login ip=203\\.0\\.113\\.10 status=200 durationMs=\\d+")
          .doesNotContain("secret", "proxy-prefix");
        assertThat(context.getBean("authRequestLoggingFilterRegistration", FilterRegistrationBean.class)
          .isEnabled()).isFalse();
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
          .contains("path=/api/auth/login", "status=401");
        assertThat(logs.list.get(1).getFormattedMessage())
          .contains("path=/api/auth/school/email/request", "status=403");
    }

    @Test
    @DisplayName("요청 제한 필터에서 중단된 로그인도 429 로 기록한다")
    void rateLimitRejectionIsLogged() throws Exception {
        mockMvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")).andExpect(status().isTooManyRequests());

        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(1).getFormattedMessage())
          .contains("path=/api/auth/login", "status=429");
    }

    @Test
    @DisplayName("헬스체크, OPTIONS, 로깅 대상이 아닌 인증 경로는 기록하지 않는다")
    void unrelatedRequestsAreNotLogged() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(options("/api/auth/login")
          .header("Origin", "http://localhost:3000")
          .header("Access-Control-Request-Method", "POST"))
          .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/test-only")).andExpect(status().isOk());

        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("필터 밖으로 전파되는 예외를 200 으로 기록하지 않고 그대로 전달한다")
    void unhandledExceptionIsLoggedAs500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("exception-secret");

        assertThatThrownBy(() -> context.getBean(AuthRequestLoggingFilter.class)
          .doFilter(request, response, (req, res) -> { throw failure; }))
          .isSameAs(failure);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
          .contains("status=500")
          .doesNotContain("exception-secret");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(JwtTokenProvider.class));
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

        // 404 예외 처리와 무관하게, 로깅 목록 밖의 인증 경로가 제외되는지 검증한다.
        @PostMapping("/api/auth/test-only")
        String otherAuthApi() {
            return "ok";
        }

        @GetMapping("/health")
        String health() {
            return "up";
        }
    }
}
