package com.example.mateon.aichat.repository;

import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.dto.AiChatSessionSummary;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

    /**
     * 메시지를 붙이기 전에 스레드를 잠그고 가져온다.
     *
     * <p>
     * seq 채번이 {@code lastSeq} 카운터라, 같은 스레드에 동시 요청이 들어오면 둘 다 같은 값을
     * 읽어 uk_ai_chat_messages_seq 를 위반할 수 있다. 이 잠금이 그 구간을 직렬화한다. 잡고 있는
     * 트랜잭션은 LLM 호출을 포함하지 않는 짧은 구간이라(TX1) 풀을 마르게 하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AiChatSession s WHERE s.id = :id")
    Optional<AiChatSession> findWithLockById(@Param("id") Long id);

    /**
     * 사이드바 목록. 마지막 메시지를 상관 서브쿼리로 함께 뽑는다 — 목록 N 건에 대해 각각
     * 조회하면 N+1 이 된다.
     *
     * <p>
     * {@code m.seq = s.lastSeq} 로 마지막 한 줄을 집는다. 발화가 없는 새 스레드는
     * lastSeq 가 0 이라 매칭되는 행이 없고 null 이 나온다.
     */
    @Query("""
            SELECT new com.example.mateon.aichat.dto.AiChatSessionSummary(
                       s.id, s.title,
                       (SELECT m.content FROM AiChatMessage m
                         WHERE m.chatSession = s AND m.seq = s.lastSeq),
                       s.updatedAt)
            FROM AiChatSession s
            WHERE s.user.id = :userId
            ORDER BY s.updatedAt DESC
            """)
    List<AiChatSessionSummary> findSummariesByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     * 가장 최근에 쓴 스레드. 스레드를 지정하지 않는 레거시 경로
     * ({@code POST /api/matching/intents/messages}) 전용이다.
     */
    Optional<AiChatSession> findFirstByUserIdOrderByUpdatedAtDesc(Long userId);
}
