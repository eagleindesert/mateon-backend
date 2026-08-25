package com.example.mateon.support;

import com.example.mateon.auth.jwt.JwtTokenProvider;
import com.example.mateon.common.config.JwtProperties;

/**
 * JWT 를 쓰는 단위 테스트의 공통 설정.
 *
 * <p>
 * 공용화하는 이유는 반복 때문이 아니라 <b>틀렸을 때의 실패가 헷갈리기 때문</b>이다.
 * jjwt 의 {@code Keys.hmacShaKeyFor} 는 HS256 규격상 256비트(32바이트) 미만 키를 거부하며
 * {@code WeakKeyException} 을 던지는데, 이 예외만 보고는 "테스트 시크릿이 짧다"는 사실에
 * 도달하기 어렵다. 그래서 충분히 긴 시크릿을 한 곳에 두고 세 테스트 클래스가 공유한다.
 * (JwtTokenProviderTest, JwtAuthenticationFilterTest, StompAuthChannelInterceptorTest)
 */
public final class TestJwt {

    /**
     * 32바이트를 넘는 테스트 전용 시크릿. 운영 값과 무관하다.
     */
    public static final String SECRET = "mateon-test-secret-key-for-junit-only-0123456789";

    public static final long EXPIRATION_MS = 3_600_000L;       // 1시간
    public static final long REFRESH_EXPIRATION_MS = 604_800_000L; // 7일

    private TestJwt() {
    }

    public static JwtProperties properties() {
        return properties(EXPIRATION_MS, REFRESH_EXPIRATION_MS);
    }

    /**
     * 만료 동작을 보려면 음수 만료를 넘긴다 (이미 지난 exp 로 토큰이 만들어진다).
     */
    public static JwtProperties properties(long expirationMs, long refreshExpirationMs) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpiration(expirationMs);
        properties.setRefreshExpiration(refreshExpirationMs);
        return properties;
    }

    public static JwtTokenProvider provider() {
        return new JwtTokenProvider(properties());
    }
}
