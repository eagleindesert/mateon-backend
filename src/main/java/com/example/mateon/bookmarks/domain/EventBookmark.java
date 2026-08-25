package com.example.mateon.bookmarks.domain;

import com.example.mateon.events.models.Event;
import com.example.mateon.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 활동 북마크(즐겨찾기). 유저가 공모전/대외활동을 찜해 둔 '현재 상태'다.
 *
 * <p>
 * 상태 전이가 없어서 TeamOffer 처럼 도메인 메서드를 두지 않는다 — 있음/없음 두 가지뿐이고,
 * 해제는 상태 컬럼을 바꾸는 게 아니라 행을 지우는 것이다. 언제 찜했는지({@link #createdAt})는
 * 목록 정렬 기준이라 남기지만, 언제 풀었는지는 아무도 묻지 않으므로 남기지 않는다.
 *
 * <p>
 * setter 를 열지 않는다. 생성 후 바뀔 필드가 하나도 없다.
 */
@Entity
@Table(
  name = "event_bookmarks",
  uniqueConstraints = @UniqueConstraint(
    name = "uq_event_bookmarks_pair", columnNames = {"user_id", "event_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class EventBookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public EventBookmark(User user, Event event) {
        this.user = user;
        this.event = event;
    }
}
