package com.example.mateon.auth.jwt;

import com.example.mateon.support.TestJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰 발급·검증의 계약을 고정한다.
 *
 * <p>가장 중요한 건 <b>같은 밀리초에 두 번 발급해도 토큰 문자열이 달라야 한다</b>는 것이다.
 * {@code refresh_tokens.token} 에 UNIQUE 제약이 걸려 있어서, sub/iat/exp 만으로 서명하면
 * 1초 안에 두 번 로그인한 유저가 똑같은 문자열을 받고 저장 시점에 제약 위반으로 터진다.
 * 지금은 {@code jti} 로 랜덤 UUID 를 넣어 막고 있는데, 이 한 줄이 "쓸모없어 보인다"는 이유로
 * 지워지기 딱 좋은 코드라 테스트로 붙잡아 둔다.
 *
 * <p>두 번째는 <b>{@code validateToken} 이 예외를 던지지 않는다</b>는 것이다. 이 메서드는
 * 모든 요청을 통과하는 필터에서 불린다 — 여기서 예외가 새면 잘못된 토큰 하나가 500 이 된다.
 */
class JwtTokenProviderTest {

    private final JwtTokenProvider provider = TestJwt.provider();

    @Nested
    @DisplayName("발급")
    class Issue {

        @Test
        @DisplayName("액세스 토큰의 subject 는 userId 문자열로 왕복한다")
        void subjectRoundTrips() {
            String token = provider.createAccessToken(42L);

            assertThat(provider.getUserIdFromToken(token)).isEqualTo(42L);
        }

        @Test
        @DisplayName("같은 밀리초에 만든 리프레시 토큰 200개가 전부 다르다 (jti 가드)")
        void refreshTokensAreAlwaysUnique() {
            Set<String> tokens = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                tokens.add(provider.createRefreshToken(1L));
            }

            // 중복이 하나라도 생기면 refresh_tokens.token UNIQUE 제약에 걸려 로그인이 실패한다.
            assertThat(tokens).hasSize(200);
        }

        @Test
        @DisplayName("액세스와 리프레시는 만료가 다르다 — 같으면 만료 설정이 한쪽만 반영된 것이다")
        void accessAndRefreshHaveDifferentExpiry() {
            JwtTokenProvider shortLived = new JwtTokenProvider(TestJwt.properties(1_000L, 600_000L));

            String access = shortLived.createAccessToken(1L);
            String refresh = shortLived.createRefreshToken(1L);

            assertThat(access).isNotEqualTo(refresh);
            assertThat(shortLived.validateToken(access)).isTrue();
            assertThat(shortLived.validateToken(refresh)).isTrue();
        }
    }

    @Nested
    @DisplayName("검증 — 어떤 입력에도 예외를 던지지 않고 boolean 으로만 답한다")
    class Validate {

        @Test
        @DisplayName("정상 토큰은 true")
        void validToken() {
            assertThat(provider.validateToken(provider.createAccessToken(1L))).isTrue();
        }

        @Test
        @DisplayName("서명이 변조된 토큰은 false")
        void tamperedToken() {
            String token = provider.createAccessToken(1L);
            String tampered = token.substring(0, token.length() - 3) + "abc";

            assertThat(provider.validateToken(tampered)).isFalse();
        }

        @Test
        @DisplayName("다른 시크릿으로 서명된 토큰은 false")
        void foreignSignature() {
            var otherProps = TestJwt.properties();
            otherProps.setSecret("completely-different-secret-key-0123456789abcdef");
            String foreign = new JwtTokenProvider(otherProps).createAccessToken(1L);

            assertThat(provider.validateToken(foreign)).isFalse();
        }

        @Test
        @DisplayName("만료된 토큰은 false (예외가 아니다)")
        void expiredToken() {
            JwtTokenProvider expired = new JwtTokenProvider(TestJwt.properties(-1_000L, -1_000L));
            String token = expired.createAccessToken(1L);

            assertThat(expired.validateToken(token)).isFalse();
        }

        @Test
        @DisplayName("JWT 가 아닌 쓰레기 문자열도 false")
        void garbage() {
            assertThat(provider.validateToken("not-a-jwt")).isFalse();
            assertThat(provider.validateToken("a.b.c")).isFalse();
        }

        @Test
        @DisplayName("빈 문자열과 null 도 false — 필터가 그대로 넘길 수 있어야 한다")
        void emptyAndNull() {
            assertThat(provider.validateToken("")).isFalse();
            assertThat(provider.validateToken(null)).isFalse();
        }
    }
}
