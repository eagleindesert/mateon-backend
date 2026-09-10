package com.example.mateon.auth.config;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    private AuthRateLimitProperties properties;
    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new AuthRateLimitProperties();
        properties.setWindow(Duration.ofMinutes(10));
        properties.setIpPerWindow(3);
        properties.setLoginEmailPerWindow(2);
        properties.setResetEmailPerWindow(2);
        limiter = new AuthRateLimiter(properties);
    }

    @Test
    @DisplayName("한도 안에서는 true, 넘으면 false")
    void tryAcquireRespectsLimit() {
        assertThat(limiter.tryAcquire("ip:a", 2)).isTrue();
        assertThat(limiter.tryAcquire("ip:a", 2)).isTrue();
        assertThat(limiter.tryAcquire("ip:a", 2)).isFalse();
    }

    @Test
    @DisplayName("키가 다르면 카운트가 독립이다")
    void keysAreIndependent() {
        limiter.tryAcquire("ip:a", 1);
        assertThat(limiter.tryAcquire("ip:b", 1)).isTrue();
    }

    @Test
    @DisplayName("로그인 이메일 한도를 넘기면 AUTH_RATE_LIMITED")
    void loginEmailLimit() {
        limiter.checkLoginEmail("a@b.ac.kr");
        limiter.checkLoginEmail("A@B.ac.kr");

        assertThatThrownBy(() -> limiter.checkLoginEmail("a@b.ac.kr"))
          .isInstanceOf(MateonException.class)
          .extracting("errorCode").isEqualTo(ErrorCode.AUTH_RATE_LIMITED);
    }

    @Test
    @DisplayName("다른 이메일은 로그인 한도를 공유하지 않는다")
    void loginEmailsAreIndependent() {
        limiter.checkLoginEmail("a@b.ac.kr");
        limiter.checkLoginEmail("a@b.ac.kr");

        limiter.checkLoginEmail("c@d.ac.kr");
    }
}
