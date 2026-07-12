package com.example.mateon.chat.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_rooms")
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class ChatRoom {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomType type; // DM / GROUP

    // GROUP 방이 특정 Team 과 연결될 때 사용. (raw Long id 컨벤션은 Team.leaderUserId 등과 동일)
    // DM 방은 null.
    @Column(name = "team_id")
    private Long teamId;

    // GROUP 방 이름. DM 방은 상대 이름을 FE 에서 표시하므로 null 허용.
    @Column(length = 100)
    private String title;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 새 메시지가 올 때마다 갱신 → 방 목록 최신순 정렬에 사용
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public ChatRoom(RoomType type, Long teamId, String title) {
        this.type = type;
        this.teamId = teamId;
        this.title = title;
    }

    // 새 메시지 수신 시 updatedAt 을 강제로 갱신하기 위한 터치 메서드
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
