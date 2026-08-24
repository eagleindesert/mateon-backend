package com.example.mateon.chat.config;

import com.example.mateon.common.config.CorsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final CorsProperties corsProperties;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // FE 는 이 엔드포인트로 STOMP 핸드셰이크. CORS 는 SecurityConfig 와 동일한 정책을 따른다.
        // 같은 경로에 (1) 네이티브 WebSocket 과 (2) SockJS 를 모두 등록하여
        // FE 가 @stomp/stompjs 의 native WebSocket / SockJS 어느 쪽이든 접속할 수 있게 한다.
        applyAllowedOrigins(registry.addEndpoint("/ws-stomp")); // 네이티브 ws://.../ws-stomp
        applyAllowedOrigins(registry.addEndpoint("/ws-stomp"))
          .withSockJS(); // SockJS fallback: /ws-stomp/**
    }

    /**
     * 허용 오리진은 REST 와 같은 값을 쓴다 ({@link CorsProperties}).
     *
     * <p>
     * setAllowedOrigins 가 아니라 setAllowedOriginPatterns 인 이유는 SecurityConfig 쪽과 같다 —
     * 정확한 오리진도 패턴으로 매칭되고, 로컬 기본값 "*" 는 Patterns 여야만 쓸 수 있다.
     */
    private StompWebSocketEndpointRegistration applyAllowedOrigins(StompWebSocketEndpointRegistration registration) {
        return registration.setAllowedOriginPatterns(
          corsProperties.getAllowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 인메모리 SimpleBroker. /topic 구독으로 방 브로드캐스트 수신.
        registry.enableSimpleBroker("/topic");
        // @MessageMapping 핸들러로 라우팅되는 클라이언트 발행 prefix.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // CONNECT 프레임 JWT 인증
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
