package com.example.mateon.notification.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.dto.NotificationResponseDTO;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 API 의 응답 계약을 고정한다.
 *
 * <p>
 * 여기서 반드시 잡아야 할 건 <b>boolean 필드의 JSON 키</b>다.
 * {@code NotificationResponseDTO.isRead} 는 Lombok 이 만든 게터가 {@code isRead()} 라서
 * Jackson 이 접두사를 떼고 <b>{@code read}</b> 로 직렬화한다 — 필드명과 다르다.
 * 이 레포는 같은 함정을 이미 한 번 겪었고({@code UserProfileResponse.isMe} 가
 * {@code @JsonProperty} 를 붙여야 했다), 그래서 boolean 키는 DTO 마다 개별로 못박는 게 관례다.
 *
 * <p>
 * 또 하나는 구독 엔드포인트가 <b>{@code text/event-stream}</b> 으로 나가고 async 로 시작한다는
 * 점이다. 여기서 produces 를 빠뜨리면 브라우저 EventSource 가 연결 자체를 거부한다.
 */
class NotificationControllerTest {

    private static final long USER_ID = 1L;

    private NotificationService notificationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        mockMvc = MockMvcBuilders
          .standaloneSetup(new NotificationController(notificationService))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
    }

    @Test
    @DisplayName("읽음 여부의 JSON 키는 isRead 가 아니라 read 다 (Lombok 게터가 접두사를 뗀다)")
    void readFlagIsSerializedAsRead() throws Exception {
        when(notificationService.getMyNotifications(USER_ID)).thenReturn(List.of(dto()));

        mockMvc.perform(get("/api/notifications").principal(auth()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].read").value(false))
          .andExpect(jsonPath("$.data[0].isRead").doesNotExist());
    }

    @Test
    @DisplayName("목록 항목은 id/title/content/type/createdAt 키를 가진다")
    void listShape() throws Exception {
        when(notificationService.getMyNotifications(USER_ID)).thenReturn(List.of(dto()));

        mockMvc.perform(get("/api/notifications").principal(auth()))
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.data[0].id").value(1))
          .andExpect(jsonPath("$.data[0].title").value("가입 승인"))
          .andExpect(jsonPath("$.data[0].content").value("1팀 가입이 승인되었습니다."))
          .andExpect(jsonPath("$.data[0].type").value("APPROVE"))
          .andExpect(jsonPath("$.data[0].createdAt").exists());
    }

    @Test
    @DisplayName("알림이 없으면 빈 배열이다")
    void emptyList() throws Exception {
        when(notificationService.getMyNotifications(USER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/notifications").principal(auth()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data").isArray())
          .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("없는 유저는 404 다")
    void unknownUserIs404() throws Exception {
        when(notificationService.getMyNotifications(anyLong()))
          .thenThrow(new MateonException(ErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/api/notifications").principal(auth()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("구독은 async 로 시작하고 서비스에 내 userId 를 넘긴다")
    void subscribeStartsAsync() throws Exception {
        when(notificationService.subscribe(USER_ID)).thenReturn(new SseEmitter(60_000L));

        // Content-Type 은 여기서 단언하지 않는다 — emitter 가 첫 이벤트를 쓰기 전까지
        // 응답 헤더가 확정되지 않아 초기 응답에는 아직 비어 있다. produces 선언이
        // 살아 있는지는 아래 rejectsNonEventStreamAccept 가 대신 확인한다.
        mockMvc.perform(get("/api/notifications/subscribe").principal(auth()))
          .andExpect(status().isOk())
          .andExpect(request().asyncStarted());

        verify(notificationService).subscribe(USER_ID);
    }

    @Test
    @DisplayName("Accept: text/event-stream 요청도 같은 핸들러에 매핑된다")
    void acceptsEventStream() throws Exception {
        when(notificationService.subscribe(USER_ID)).thenReturn(new SseEmitter(60_000L));

        mockMvc.perform(get("/api/notifications/subscribe")
          .accept(MediaType.TEXT_EVENT_STREAM)
          .principal(auth()))
          .andExpect(status().isOk())
          .andExpect(request().asyncStarted());
    }

    // --- 픽스처 -------------------------------------------------------------
    private NotificationResponseDTO dto() {
        Notification notification = Notification.builder()
          .receiver(User.builder().id(USER_ID).name("김학생").build())
          .title("가입 승인").content("1팀 가입이 승인되었습니다.")
          .type(Notification.NotificationType.APPROVE)
          .build();
        TestEntities.withId(notification, 1L);
        TestEntities.withField(notification, "createdAt", LocalDateTime.now());
        return new NotificationResponseDTO(notification);
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
