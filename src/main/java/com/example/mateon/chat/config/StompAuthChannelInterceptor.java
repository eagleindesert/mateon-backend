package com.example.mateon.chat.config;

import com.example.mateon.auth.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;

/**
 * STOMP CONNECT 프레임에서 JWT 를 검증하고 사용자를 인증한다.
 * 기존 HTTP {@code JwtAuthenticationFilter} 는 WebSocket 업그레이드/프레임에는 적용되지 않으므로
 * WebSocket 경로 전용 인증을 여기서 처리한다. userId 식별자는 HTTP 와 동일하게
 * {@code UsernamePasswordAuthenticationToken(principal = String userId)} 로 세팅한다.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // CONNECT 시점에만 인증을 수행하고, 이후 프레임은 세션에 저장된 user 를 재사용한다.
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = resolveToken(accessor.getFirstNativeHeader("Authorization"));

            if (!StringUtils.hasText(token) || !jwtTokenProvider.validateToken(token)) {
                throw new IllegalArgumentException("유효하지 않은 인증 토큰입니다.");
            }

            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            String.valueOf(userId),
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

            // 이후 @MessageMapping 핸들러에서 Principal.getName() == userId 로 사용
            accessor.setUser(authentication);
        }

        return message;
    }

    private String resolveToken(String bearerToken) {
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return bearerToken; // "Bearer " 접두어 없이 토큰만 보내는 클라이언트도 허용
    }
}
