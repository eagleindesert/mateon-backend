package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.IntentMessageRole;
import com.example.mateon.matching.domain.MatchingIntentMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MatchingIntentMessageRepository extends JpaRepository<MatchingIntentMessage, Long> {

    /** 대화 전체 (USER + ASSISTANT). GET /session 복원용. */
    List<MatchingIntentMessage> findBySessionIdOrderBySeqAsc(Long sessionId);

    /**
     * FastAPI 로 보낼 USER 발화만 순서대로. 문자열만 뽑아 detach 된 값으로 넘긴다
     * (TX 밖에서 쓰이므로 엔티티를 넘기면 LazyInitializationException).
     */
    @Query("SELECT m.message FROM MatchingIntentMessage m " +
           "WHERE m.session.id = :sessionId AND m.role = :role ORDER BY m.seq ASC")
    List<String> findMessagesBySessionIdAndRole(@Param("sessionId") Long sessionId,
                                                @Param("role") IntentMessageRole role);

    /** 다음 seq 계산용. */
    int countBySessionId(Long sessionId);
}
