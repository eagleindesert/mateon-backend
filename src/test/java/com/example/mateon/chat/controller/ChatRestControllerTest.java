package com.example.mateon.chat.controller;

import com.example.mateon.chat.domain.ChatMessage;
import com.example.mateon.chat.domain.ChatRoom;
import com.example.mateon.chat.domain.RoomType;
import com.example.mateon.chat.dto.response.ChatMessageResponse;
import com.example.mateon.chat.dto.response.ChatRoomResponse;
import com.example.mateon.chat.service.ChatService;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 REST API 의 응답 계약을 고정한다.
 *
 * <p>
 * 프론트 채팅 화면이 이 세 가지 키에 직접 의존한다: 방 생성 응답의 {@code data.roomId},
 * 목록의 {@code unreadCount}/{@code partnerId}, 이력의 {@code messageId}.
 * 이름 하나만 바뀌어도 HTTP 는 200 인 채로 화면만 빈다.
 *
 * <p>
 * 또 하나 고정할 것은 <b>이력 조회의 기본 크기 30</b> 이다. 이 값은 프론트의 무한 스크롤
 * 페이지 크기와 맞물려 있어서 서버에서 조용히 바꾸면 스크롤이 어긋난다.
 *
 * <p>
 * 읽음 처리는 데이터를 돌려주지 않는데, 이때 {@code ApiResponse.success(null)} 이
 * {@code data: null} 로 나가고 {@code message} 가 {@code "성공"} 이라는 것도 확인해 둔다.
 */
class ChatRestControllerTest {

    private static final long USER_ID = 1L;
    private static final long ROOM_ID = 5L;

    private ChatService chatService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        mockMvc = MockMvcBuilders
          .standaloneSetup(new ChatRestController(chatService))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
    }

    @Nested
    @DisplayName("POST /api/chat/rooms/dm")
    class CreateDmRoom {

        @Test
        @DisplayName("응답은 data.roomId 하나다")
        void returnsRoomId() throws Exception {
            when(chatService.getOrCreateDmRoom(USER_ID, 2L)).thenReturn(room());

            mockMvc.perform(post("/api/chat/rooms/dm")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"targetUserId\":2}")
              .principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data.roomId").value(5));
        }

        @Test
        @DisplayName("targetUserId 가 없으면 서비스까지 가지 않고 400 이다")
        void requiresTargetUserId() throws Exception {
            mockMvc.perform(post("/api/chat/rooms/dm")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}")
              .principal(auth()))
              .andExpect(status().isBadRequest());

            verify(chatService, never()).getOrCreateDmRoom(anyLong(), anyLong());
        }

        @Test
        @DisplayName("자기 자신과의 DM 은 400 이다")
        void selfDmIs400() throws Exception {
            when(chatService.getOrCreateDmRoom(anyLong(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.CANNOT_CHAT_WITH_SELF));

            mockMvc.perform(post("/api/chat/rooms/dm")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"targetUserId\":1}")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.CANNOT_CHAT_WITH_SELF.getMessage()));
        }
    }

    @Nested
    @DisplayName("GET /api/chat/rooms")
    class MyRooms {

        @Test
        @DisplayName("목록 항목은 roomId/type/partnerId/unreadCount 키를 가진다")
        void roomListShape() throws Exception {
            when(chatService.getMyRooms(USER_ID)).thenReturn(List.of(
              ChatRoomResponse.builder()
                .room(room()).title("김상대").partnerId(2L)
                .lastMessage("안녕하세요").lastMessageAt(LocalDateTime.now())
                .unreadCount(3)
                .build()));

            mockMvc.perform(get("/api/chat/rooms").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data[0].roomId").value(5))
              .andExpect(jsonPath("$.data[0].type").value("DM"))
              .andExpect(jsonPath("$.data[0].title").value("김상대"))
              .andExpect(jsonPath("$.data[0].partnerId").value(2))
              .andExpect(jsonPath("$.data[0].lastMessage").value("안녕하세요"))
              .andExpect(jsonPath("$.data[0].unreadCount").value(3));
        }

        @Test
        @DisplayName("참여한 방이 없으면 빈 배열이다 (null 이 아니다)")
        void emptyList() throws Exception {
            when(chatService.getMyRooms(USER_ID)).thenReturn(List.of());

            mockMvc.perform(get("/api/chat/rooms").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").isArray())
              .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /api/chat/rooms/{roomId}/messages")
    class Messages {

        @Test
        @DisplayName("기본 페이지 크기는 30 이다 (프론트 무한 스크롤과 맞물린 값)")
        void defaultSizeIs30() throws Exception {
            when(chatService.getMessages(eq(USER_ID), eq(ROOM_ID), isNull(), eq(30))).thenReturn(List.of());

            mockMvc.perform(get("/api/chat/rooms/5/messages").principal(auth()))
              .andExpect(status().isOk());

            verify(chatService).getMessages(USER_ID, ROOM_ID, null, 30);
        }

        @Test
        @DisplayName("before/size 는 그대로 서비스에 넘어간다")
        void passesBeforeAndSize() throws Exception {
            when(chatService.getMessages(eq(USER_ID), eq(ROOM_ID), eq(100L), eq(10))).thenReturn(List.of());

            mockMvc.perform(get("/api/chat/rooms/5/messages")
              .param("before", "100").param("size", "10")
              .principal(auth()))
              .andExpect(status().isOk());

            verify(chatService).getMessages(USER_ID, ROOM_ID, 100L, 10);
        }

        @Test
        @DisplayName("메시지 항목은 messageId/senderId/senderName/content 키를 가진다")
        void messageShape() throws Exception {
            when(chatService.getMessages(anyLong(), anyLong(), any(), org.mockito.ArgumentMatchers.anyInt()))
              .thenReturn(List.of(messageResponse()));

            mockMvc.perform(get("/api/chat/rooms/5/messages").principal(auth()))
              .andExpect(jsonPath("$.data[0].messageId").value(77))
              .andExpect(jsonPath("$.data[0].roomId").value(5))
              .andExpect(jsonPath("$.data[0].senderId").value(2))
              .andExpect(jsonPath("$.data[0].senderName").value("김상대"))
              .andExpect(jsonPath("$.data[0].content").value("안녕하세요"))
              .andExpect(jsonPath("$.data[0].createdAt").exists());
        }

        @Test
        @DisplayName("남의 방 이력을 보려 하면 400 이다")
        void nonMemberIs400() throws Exception {
            when(chatService.getMessages(anyLong(), anyLong(), any(), org.mockito.ArgumentMatchers.anyInt()))
              .thenThrow(new MateonException(ErrorCode.NOT_ROOM_MEMBER));

            mockMvc.perform(get("/api/chat/rooms/5/messages").principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.NOT_ROOM_MEMBER.getMessage()));
        }

        @Test
        @DisplayName("roomId 가 숫자가 아니면 400 이다 (타입 불일치 핸들러)")
        void nonNumericRoomId() throws Exception {
            mockMvc.perform(get("/api/chat/rooms/abc/messages").principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."));
        }
    }

    @Nested
    @DisplayName("POST /api/chat/rooms/{roomId}/read")
    class MarkAsRead {

        @Test
        @DisplayName("성공 응답은 data 가 null 이다")
        void returnsNullData() throws Exception {
            mockMvc.perform(post("/api/chat/rooms/5/read")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"lastReadMessageId\":42}")
              .principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data").doesNotExist());

            verify(chatService).markAsRead(USER_ID, ROOM_ID, 42L);
        }

        @Test
        @DisplayName("lastReadMessageId 가 없으면 400 이다")
        void requiresLastReadMessageId() throws Exception {
            mockMvc.perform(post("/api/chat/rooms/5/read")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}")
              .principal(auth()))
              .andExpect(status().isBadRequest());

            verify(chatService, never()).markAsRead(anyLong(), anyLong(), anyLong());
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private ChatRoom room() {
        ChatRoom room = TestEntities.withId(ChatRoom.builder().type(RoomType.DM).build(), ROOM_ID);
        room.touch();
        return room;
    }

    private ChatMessageResponse messageResponse() {
        ChatMessage message = TestEntities.withId(ChatMessage.builder()
          .room(room())
          .sender(User.builder().id(2L).name("김상대").build())
          .content("안녕하세요")
          .build(), 77L);
        TestEntities.withField(message, "createdAt", LocalDateTime.now());
        return new ChatMessageResponse(message);
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
