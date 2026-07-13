package com.example.mateon.chat.dto.response;

import com.example.mateon.chat.domain.ChatRoom;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// 방 목록 조회 응답
@Getter
public class ChatRoomResponse {
    private final Long roomId;
    private final String type;            // DM / GROUP
    private final Long teamId;            // GROUP 방일 때 연결된 팀 id (없으면 null)
    private final String title;           // GROUP 방 이름 (DM 은 상대 이름으로 채움)
    private final Long partnerId;         // DM 상대 사용자 id (GROUP 은 null)
    private final String lastMessage;     // 마지막 메시지 미리보기 (없으면 null)
    private final LocalDateTime lastMessageAt;
    private final long unreadCount;       // 안읽음 메시지 수

    @Builder
    public ChatRoomResponse(ChatRoom room, String title, Long partnerId,
                            String lastMessage, LocalDateTime lastMessageAt, long unreadCount) {
        this.roomId = room.getId();
        this.type = room.getType().name();
        this.teamId = room.getTeamId();
        this.title = title;
        this.partnerId = partnerId;
        this.lastMessage = lastMessage;
        this.lastMessageAt = lastMessageAt;
        this.unreadCount = unreadCount;
    }
}
