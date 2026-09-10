package com.example.mateon.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPropertiesTest {

    @Test
    @DisplayName("재설정 링크는 웹 origin 이고 백엔드 호스트가 아니다")
    void passwordResetLinkGoesToWeb() {
        AppProperties properties = new AppProperties();
        properties.setWebBaseUrl("https://app.example.com");
        properties.setWebResetPasswordPath("/reset-password");

        assertThat(properties.passwordResetLink("abc"))
          .isEqualTo("https://app.example.com/reset-password?token=abc");
    }

    @Test
    @DisplayName("origin 끝 슬래시와 path 앞 슬래시가 없어도 한 줄로 붙는다")
    void normalizesSlashes() {
        AppProperties properties = new AppProperties();
        properties.setWebBaseUrl("http://localhost:5173/");
        properties.setWebResetPasswordPath("reset-password");

        assertThat(properties.passwordResetLink("t"))
          .isEqualTo("http://localhost:5173/reset-password?token=t");
    }

    @Test
    @DisplayName("WEB_BASE_URL 이 비어 있거나 플레이스홀더면 부팅을 막는다")
    void rejectsMissingWebBaseUrl() {
        AppProperties properties = new AppProperties();
        properties.setWebBaseUrl("${WEB_BASE_URL}");

        assertThatThrownBy(properties::validateWebBaseUrl)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("WEB_BASE_URL");
    }
}
