package com.example.mateon.chat.dto.response;

import com.example.mateon.chat.domain.ChatRoom;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// 방 목록 조회 응답
@Schema(description = "채팅방 한 건. 목록 화면을 방을 열지 않고도 그릴 수 있게 미리보기와 안읽음 수가 함께 실린다.")
@Getter
public class ChatRoomResponse {
    private final Long roomId;
    @Schema(description = "방 종류.", allowableValues = {"DM", "GROUP"})
    private final String type;            // DM / GROUP
    @Schema(description = "GROUP 방일 때 연결된 팀 id. DM 이면 null.")
    private final Long teamId;            // GROUP 방일 때 연결된 팀 id (없으면 null)
    @Schema(description = "화면에 띄울 방 이름. **DM 이면 상대 이름으로 채워져 있으므로** 따로 조회하지 않아도 된다.")
    private final String title;           // GROUP 방 이름 (DM 은 상대 이름으로 채움)
    @Schema(description = "DM 상대의 userId. GROUP 이면 null.")
    private final Long partnerId;         // DM 상대 사용자 id (GROUP 은 null)
    @Schema(description = "마지막 메시지 미리보기. 아직 대화가 없으면 null.")
    private final String lastMessage;     // 마지막 메시지 미리보기 (없으면 null)
    private final LocalDateTime lastMessageAt;
    @Schema(description = "안 읽은 메시지 수. POST /rooms/{roomId}/read 로 갱신된다.")
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
