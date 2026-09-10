package com.example.mateon.auth.config;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthRateLimitFilterTest {

    private AuthRateLimiter limiter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setWindow(Duration.ofMinutes(10));
        properties.setIpPerWindow(2);
        limiter = new AuthRateLimiter(properties);
        AuthRateLimitFilter filter = new AuthRateLimitFilter(limiter, properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
          .addFilters(filter)
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
    }

    @Test
    @DisplayName("한도 안의 POST /login 은 통과한다")
    void loginUnderLimit() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("IP 한도를 넘기면 429 AUTH_RATE_LIMITED")
    void loginOverLimitIs429() throws Exception {
        mockMvc.perform(post("/api/auth/login").content("{}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login").content("{}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login").content("{}"))
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.message").value(ErrorCode.AUTH_RATE_LIMITED.getMessage()));
    }

    @Test
    @DisplayName("다른 경로는 한도를 세지 않는다")
    void otherPathsAreNotLimited() throws Exception {
        mockMvc.perform(post("/api/auth/login").content("{}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login").content("{}")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/signup").content("{}")).andExpect(status().isOk());
    }

    @RestController
    static class StubController {
        @PostMapping("/api/auth/login")
        public String login() {
            return "ok";
        }

        @PostMapping("/api/auth/signup")
        public String signup() {
            return "ok";
        }

        @org.springframework.web.bind.annotation.GetMapping("/health")
        public String health() {
            return "up";
        }
    }
}
