package com.example.mateon.chat.controller;

import com.example.mateon.chat.dto.request.ChatMessageRequest;
import com.example.mateon.chat.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * STOMP 메시지 핸들러는 한 줄 위임이라 검증할 게 많지 않다. 딱 하나 의미 있는 건
 * <b>{@code principal.getName()} 이 userId 라는 전제</b>다.
 *
 * <p>
 * 이 전제는 {@link com.example.mateon.chat.config.StompAuthChannelInterceptor} 가 세워 주는데,
 * 둘은 서로 다른 패키지에 있고 컴파일러가 이어 주지도 않는다. 인터셉터가 principal 을
 * UserDetails 나 이메일로 바꾸면 여기서 {@code NumberFormatException} 이 나고, 그건 STOMP
 * 핸들러 안이라 HTTP 응답도 없이 조용히 삼켜진다. 그래서 전제 자체를 테스트로 적어 둔다.
 */
class ChatStompControllerTest {

    private ChatService chatService;
    private ChatStompController controller;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        controller = new ChatStompController(chatService);
    }

    @Test
    @DisplayName("principal 이름을 userId 로 바꿔 서비스에 넘긴다")
    void delegatesWithUserIdFromPrincipal() {
        controller.sendMessage(request(5L, "안녕하세요"), principal("7"));

        verify(chatService).saveAndBroadcast(7L, 5L, "안녕하세요");
    }

    @Test
    @DisplayName("principal 이름이 숫자가 아니면 여기서 터진다 — 인터셉터가 세운 전제가 깨졌다는 신호다")
    void nonNumericPrincipalFailsFast() {
        assertThatThrownBy(() -> controller.sendMessage(request(5L, "안녕"), principal("user@example.com")))
          .isInstanceOf(NumberFormatException.class);
    }

    private ChatMessageRequest request(Long roomId, String content) {
        // STOMP 페이로드 DTO 는 세터가 없고 Jackson 이 필드로 채운다. 테스트에서도 같은 방식으로 만든다.
        ChatMessageRequest request = new ChatMessageRequest();
        ReflectionTestUtils.setField(request, "roomId", roomId);
        ReflectionTestUtils.setField(request, "content", content);
        return request;
    }

    private Principal principal(String name) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of());
    }
}
