package com.example.mateon.aichat.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * AI 채팅 스레드 하나. 사용자가 사이드바에서 골라 들어가는 그 단위다.
 *
 * <p>
 * <b>상태가 없다.</b> V30 까지는 ACTIVE/CLOSED 와 "사용자당 ACTIVE 1건" 부분 유니크 인덱스로
 * "지금 이어갈 대화"를 하나로 고정했는데, 스레드를 여러 개 갖고 골라 들어가게 되면서 그 개념이
 * 사라졌다 — 어디에 쓸지는 사용자가 요청마다 지정한다.
 *
 * <p>
 * <b>도메인 상태도 없다.</b> 진행 상황·만료 판정은 {@link AiDomainTask} 가, 추출 결과처럼
 * 도메인마다 형태가 다른 건 각 도메인 테이블이 갖는다. 여기는 "누가 언제 무슨 말을 했는가"의
 * 그릇일 뿐이라 도메인이 늘어도 컬럼이 변하지 않는다.
 *
 * <p>
 * {@link #lastSeq} 가 채번 카운터인 이유는 두 가지다. 메시지를 붙일 때마다 {@code COUNT(*)}
 * 를 도는 걸 없애는 게 하나고, 더 중요한 건 <b>이 행을 더럽혀야 {@code updatedAt} 이 갱신된다</b>
 * 는 것이다. {@code @LastModifiedDate} 는 엔티티가 더러울 때만 뜨는데 메시지 저장은 이 행을
 * 건드리지 않아서, V30 에서는 사이드바 정렬 키가 될 컬럼이 생성 시각에 멈춰 있었다.
 */
@Entity
@Table(name = "ai_chat_sessions")
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AiChatSession {

    /**
     * 사이드바 제목의 최대 길이. 첫 발화를 여기까지 잘라 쓴다.
     */
    private static final int TITLE_LENGTH = 40;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 사이드바에 보여 줄 제목. 첫 사용자 발화로 한 번만 채워진다. 그전까지는 null.
     */
    @Column(length = 100)
    private String title;

    /**
     * 마지막으로 발급한 seq. 다음 메시지는 이 값 + 1 을 받는다.
     */
    @Column(name = "last_seq", nullable = false)
    private Integer lastSeq;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 마지막 활동 시각. 사이드바 정렬 키다.
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AiChatSession(User user) {
        this.user = user;
        this.lastSeq = 0;
    }

    /**
     * 다음 seq 를 발급한다.
     *
     * <p>
     * 이 호출이 엔티티를 더럽히므로 {@code updatedAt} 도 함께 갱신된다 — 채번과 활동 시각이
     * 한 몸이어야 정렬이 거짓말을 하지 않는다. 동시 요청은 호출자가 잡은 행 잠금이 직렬화한다
     * (uk_ai_chat_messages_seq 위반을 막는 것도 그 잠금이다).
     */
    public int nextSeq() {
        return ++this.lastSeq;
    }

    /**
     * 제목이 비어 있으면 이 발화로 채운다. 이미 있으면 아무 일도 하지 않는다.
     */
    public void titleFrom(String content) {
        if (this.title != null || content == null) {
            return;
        }
        String trimmed = content.strip();
        this.title = trimmed.length() <= TITLE_LENGTH ? trimmed : trimmed.substring(0, TITLE_LENGTH);
    }

    public boolean isOwnedBy(Long userId) {
        return this.user != null && this.user.getId().equals(userId);
    }
}
