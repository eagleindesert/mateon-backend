package com.example.mateon.chat.config;

import com.example.mateon.auth.jwt.JwtTokenProvider;
import com.example.mateon.support.TestJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * WebSocket 경로의 인증을 고정한다.
 *
 * <p>
 * HTTP 쪽 {@code JwtAuthenticationFilter} 는 WebSocket 프레임에 적용되지 않는다. 그래서
 * 이 인터셉터가 없으면 채팅은 <b>인증 없이 열린 채로</b> 동작한다 — 화면상으로는 멀쩡히 잘 되기
 * 때문에 사라져도 알아채기 어렵다.
 *
 * <p>
 * 여기서 못박는 규칙은 넷이다.
 * <ol>
 * <li>인증은 CONNECT 프레임에서 <b>한 번만</b> 한다. 이후 SEND/SUBSCRIBE 는 세션에 붙은
 * user 를 재사용하므로, 그 프레임에 이상한 헤더가 있어도 건드리지 않는다. 여기에
 * 검사를 추가하면 정상 클라이언트가 첫 메시지부터 끊긴다.</li>
 * <li>principal 이름은 userId 다 — {@code ChatStompController} 가
 * {@code Long.valueOf(principal.getName())} 으로 되받는다.</li>
 * <li>토큰이 없거나 무효면 예외를 던져 연결 자체를 거부한다.</li>
 * <li><b>{@code Bearer } 접두사 없는 순수 토큰도 허용한다.</b> 의도된 관용이다
 * (구현 주석에 명시). "HTTP 와 통일하자"며 접두사를 강제하면 그렇게 붙이고 있던
 * 클라이언트가 전부 연결에 실패한다.</li>
 * </ol>
 */
class StompAuthChannelInterceptorTest {

    private final JwtTokenProvider tokenProvider = TestJwt.provider();
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(tokenProvider);
    private final MessageChannel channel = mock(MessageChannel.class);

    @Nested
    @DisplayName("CONNECT 프레임")
    class Connect {

        @Test
        @DisplayName("Bearer 토큰으로 접속하면 principal 이름이 userId 가 된다")
        void authenticatesWithBearerToken() {
            StompHeaderAccessor accessor = connectFrame("Bearer " + tokenProvider.createAccessToken(7L));

            interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), channel);

            assertThat(accessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
            assertThat(accessor.getUser().getName()).isEqualTo("7");
        }

        @Test
        @DisplayName("권한은 HTTP 쪽과 같은 ROLE_USER 다")
        void grantsRoleUser() {
            StompHeaderAccessor accessor = connectFrame("Bearer " + tokenProvider.createAccessToken(7L));

            interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), channel);

            var authentication = (UsernamePasswordAuthenticationToken) accessor.getUser();
            assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
              .containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("접두사 없는 순수 토큰도 통과시킨다 — 의도된 관용이므로 '고치면' 안 된다")
        void acceptsRawTokenWithoutBearerPrefix() {
            StompHeaderAccessor accessor = connectFrame(tokenProvider.createAccessToken(7L));

            interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), channel);

            assertThat(accessor.getUser().getName()).isEqualTo("7");
        }

        @Test
        @DisplayName("Authorization 헤더가 없으면 연결을 거부한다 (인증 없이 채팅이 열리면 안 된다)")
        void rejectsMissingHeader() {
            assertThatThrownBy(() -> preSend(connectFrame(null)))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessage("유효하지 않은 인증 토큰입니다.");
        }

        @Test
        @DisplayName("빈 헤더도 거부한다")
        void rejectsBlankHeader() {
            assertThatThrownBy(() -> preSend(connectFrame("   ")))
              .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("변조·만료 토큰도 거부한다")
        void rejectsInvalidToken() {
            assertThatThrownBy(() -> preSend(connectFrame("Bearer not-a-real-token")))
              .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("CONNECT 외의 프레임은 손대지 않는다")
    class OtherFrames {

        @Test
        @DisplayName("SEND 는 Authorization 이 쓰레기여도 그대로 통과한다 (인증은 CONNECT 때 끝났다)")
        void sendPassesThrough() {
            StompHeaderAccessor accessor = frame(StompCommand.SEND, "Bearer garbage");

            Message<?> result = preSend(accessor);

            assertThat(result).isNotNull();
            assertThat(accessor.getUser()).isNull();
        }

        @Test
        @DisplayName("SUBSCRIBE 도 마찬가지다")
        void subscribePassesThrough() {
            StompHeaderAccessor accessor = frame(StompCommand.SUBSCRIBE, null);

            assertThat(preSend(accessor)).isNotNull();
            assertThat(accessor.getUser()).isNull();
        }

        @Test
        @DisplayName("DISCONNECT 도 검사하지 않는다 — 여기서 막으면 세션 정리가 되지 않는다")
        void disconnectPassesThrough() {
            assertThat(preSend(frame(StompCommand.DISCONNECT, null))).isNotNull();
        }
    }

    @Test
    @DisplayName("STOMP 프레임이 아닌 메시지는 그대로 통과한다 (accessor 가 null 인 경우)")
    void nonStompMessagePassesThrough() {
        Message<byte[]> plain = MessageBuilder.withPayload(new byte[0]).build();

        assertThat(interceptor.preSend(plain, channel)).isSameAs(plain);
    }

    // --- 헬퍼 ---------------------------------------------------------------
    private StompHeaderAccessor connectFrame(String authorization) {
        return frame(StompCommand.CONNECT, authorization);
    }

    private StompHeaderAccessor frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private Message<?> preSend(StompHeaderAccessor accessor) {
        return interceptor.preSend(
          MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), channel);
    }
}
