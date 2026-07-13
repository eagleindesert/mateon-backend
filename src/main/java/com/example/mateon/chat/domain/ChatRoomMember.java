package com.example.mateon.chat.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_room_members", uniqueConstraints = {
        @UniqueConstraint(name = "uk_chat_room_member", columnNames = {"room_id", "user_id"})
})
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class ChatRoomMember {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 이 사용자가 이 방에서 마지막으로 읽은 메시지 id. null 이면 아직 아무것도 안 읽음.
    // 안읽음 수 = 이 값보다 큰 id 를 가진 메시지 개수.
    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Builder
    public ChatRoomMember(ChatRoom room, User user) {
        this.room = room;
        this.user = user;
    }

    public void updateLastReadMessageId(Long messageId) {
        // 뒤로 가는(작은 값) 갱신은 무시 → 읽음 위치가 되돌아가지 않도록
        if (messageId != null && (this.lastReadMessageId == null || messageId > this.lastReadMessageId)) {
            this.lastReadMessageId = messageId;
        }
    }
}
