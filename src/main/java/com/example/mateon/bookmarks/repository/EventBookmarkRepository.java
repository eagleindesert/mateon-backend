package com.example.mateon.bookmarks.repository;

import com.example.mateon.bookmarks.domain.EventBookmark;
import com.example.mateon.events.models.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EventBookmarkRepository extends JpaRepository<EventBookmark, Long> {

    boolean existsByUserIdAndEventId(Long userId, Long eventId);

    /**
     * 북마크 해제. 지운 행 수를 돌려주므로 "원래 없었다"와 "지웠다"를 호출부가 구분할 수 있다.
     */
    long deleteByUserIdAndEventId(Long userId, Long eventId);

    /**
     * 내 북마크 목록 — 북마크한 최신순(활동 시작일순이 아니다).
     *
     * <p>
     * 북마크를 먼저 읽고 활동을 다시 읽으면 N+1 이 되므로 {@code b.event} 를 바로 뽑아
     * 조인 한 번으로 끝낸다.
     *
     * <p>
     * 정렬을 {@link Pageable} 이 아니라 JPQL 에 박은 이유: 기준이 조인 대상(Event)이 아니라
     * 북마크 자신의 createdAt 이라, Sort 로 표현하면 프로퍼티 경로가 어느 쪽 것인지 헷갈린다.
     */
    @Query("SELECT b.event FROM EventBookmark b WHERE b.user.id = :userId ORDER BY b.createdAt DESC")
    Page<Event> findBookmarkedEvents(@Param("userId") Long userId, Pageable pageable);

    /**
     * 검색 응답의 bookmarked 를 채우기 위한 대조용. 활동마다 exists 를 부르면 페이지 크기만큼
     * 쿼리가 나가므로, 그 페이지에 실린 id 를 한 번에 넘겨 찜한 것만 받아 온다.
     */
    @Query("SELECT b.event.id FROM EventBookmark b WHERE b.user.id = :userId AND b.event.id IN :eventIds")
    List<Long> findBookmarkedEventIds(@Param("userId") Long userId,
      @Param("eventIds") Collection<Long> eventIds);

    /**
     * 내가 찜한 활동 id 전량. 프론트가 대조표를 통째로 들고 화면 여러 곳에서 별 아이콘을
     * 칠하고 싶을 때 쓴다.
     */
    @Query("SELECT b.event.id FROM EventBookmark b WHERE b.user.id = :userId ORDER BY b.createdAt DESC")
    List<Long> findAllBookmarkedEventIds(@Param("userId") Long userId);
}
