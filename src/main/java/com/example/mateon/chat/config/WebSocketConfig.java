package com.example.mateon.chat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    // SecurityConfig 의 CORS 디버그 플래그와 동일한 키를 공유한다 (.env 의 debug.enabled=true 로 활성화).
    @Value("${debug.enabled:false}")
    private boolean debugEnabled;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // FE 는 이 엔드포인트로 STOMP 핸드셰이크. CORS 는 SecurityConfig 와 동일한 정책을 따른다.
        // 같은 경로에 (1) 네이티브 WebSocket 과 (2) SockJS 를 모두 등록하여
        // FE 가 @stomp/stompjs 의 native WebSocket / SockJS 어느 쪽이든 접속할 수 있게 한다.
        applyAllowedOrigins(registry.addEndpoint("/ws-stomp")); // 네이티브 ws://.../ws-stomp
        applyAllowedOrigins(registry.addEndpoint("/ws-stomp"))
                .withSockJS(); // SockJS fallback: /ws-stomp/**
    }

    private StompWebSocketEndpointRegistration applyAllowedOrigins(StompWebSocketEndpointRegistration registration) {
        if (debugEnabled) {
            // allowCredentials 와 함께 와일드카드를 쓰려면 Origins 가 아닌 OriginPatterns 를 사용해야 한다.
            return registration.setAllowedOriginPatterns("*");
        }
        return registration.setAllowedOrigins("http://localhost:3000", "http://localhost:5173");
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
