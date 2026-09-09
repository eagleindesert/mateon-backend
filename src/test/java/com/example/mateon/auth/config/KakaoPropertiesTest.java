package com.example.mateon.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoPropertiesTest {

    @Test
    @DisplayName("허용 목록에 있는 URI 만 통과한다")
    void allowlistIsExact() {
        KakaoProperties properties = new KakaoProperties();
        properties.setRedirectUris(List.of("http://localhost:5173/auth/kakao/callback"));

        assertThat(properties.isAllowedRedirectUri("http://localhost:5173/auth/kakao/callback")).isTrue();
        assertThat(properties.isAllowedRedirectUri("https://evil.example/callback")).isFalse();
        assertThat(properties.isAllowedRedirectUri(null)).isFalse();
    }

    @Test
    @DisplayName("redirect-uris 가 비어 있거나 플레이스홀더면 부팅을 막는다")
    void rejectsUnresolvedRedirectUris() {
        KakaoProperties properties = new KakaoProperties();
        properties.setRedirectUris(List.of("${KAKAO_REDIRECT_URIS}"));

        assertThatThrownBy(properties::validateRedirectUris)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("KAKAO_REDIRECT_URIS");
    }
}
