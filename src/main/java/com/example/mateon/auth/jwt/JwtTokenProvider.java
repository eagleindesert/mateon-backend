package com.example.mateon.auth.jwt;

import com.example.mateon.common.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    /**
     * 액세스·리프레시 토큰을 가르는 클레임 이름. 두 토큰은 만료 시간만 다르고 서명·구조가 같아서,
     * 이 클레임이 없으면 7일짜리 리프레시 토큰이 API 호출에 그대로 통한다.
     */
    static final String TYPE_CLAIM = "type";

    /**
     * 토큰 용도. 값은 클레임에 문자열로 실린다.
     *
     * <p>
     * {@code ACCESS} 는 API 인증({@code JwtAuthenticationFilter}, STOMP CONNECT)에만,
     * {@code REFRESH} 는 {@code /api/auth/token/refresh} 에만 통한다.
     */
    public enum TokenType {
        ACCESS("access"),
        REFRESH("refresh");

        private final String value;

        TokenType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final JwtProperties jwtProperties;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId) {
        return createToken(userId, TokenType.ACCESS, jwtProperties.getExpiration());
    }

    public String createRefreshToken(Long userId) {
        return createToken(userId, TokenType.REFRESH, jwtProperties.getRefreshExpiration());
    }

    private String createToken(Long userId, TokenType type, long validityMillis) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + validityMillis);

        // jti(랜덤 UUID) 를 넣어 같은 초에 재발급해도 토큰 문자열이 항상 유일하도록 한다.
        // (없으면 sub+iat+exp 만으로 동일 토큰이 생성되어 refresh_tokens.token UNIQUE 제약과 충돌한다.)
        return Jwts.builder()
          .setId(UUID.randomUUID().toString())
          .setSubject(String.valueOf(userId))
          .claim(TYPE_CLAIM, type.value())
          .setIssuedAt(now)
          .setExpiration(expiryDate)
          .signWith(getSigningKey())
          .compact();
    }

    public Long getUserIdFromToken(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /**
     * 서명·만료가 유효하고 {@code type} 클레임이 {@code expected} 와 같을 때만 true.
     *
     * <p>
     * 용도를 안 보는 검증은 일부러 두지 않는다. 클레임이 없는 토큰(이 클레임을 넣기 전에
     * 발급된 것)도 false 라, 배포 직후에는 모든 사용자가 재로그인해야 한다.
     */
    public boolean validateToken(String token, TokenType expected) {
        try {
            String type = parseClaims(token).get(TYPE_CLAIM, String.class);
            return expected.value().equals(type);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
          .setSigningKey(getSigningKey())
          .build()
          .parseClaimsJws(token)
          .getBody();
    }
}
