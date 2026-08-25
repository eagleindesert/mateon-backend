package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.MatchingIntentSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MatchingIntentSessionRepository extends JpaRepository<MatchingIntentSession, Long> {

    /**
     * 상위 작업에 매달린 매칭 행을 찾는다. 1:1 이라(uk_matching_intent_sessions_task)
     * Optional 로 받는다.
     *
     * <p>
     * "진행 중인 세션" 조회가 여기 없는 건 의도적이다 — 수명은 상위가 갖고 있어서, 살아 있는
     * 작업을 먼저 찾은 뒤 그 id 로 여기 온다. 이쪽에 status 조회를 남겨 두면 답이 두 곳에서
     * 나오게 된다.
     */
    Optional<MatchingIntentSession> findByTaskId(Long taskId);
}
