package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.IntentSessionStatus;
import com.example.mateon.matching.domain.MatchingIntentSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MatchingIntentSessionRepository extends JpaRepository<MatchingIntentSession, Long> {

    /**
     * 진행 중인 세션을 찾는다. 사용자당 IN_PROGRESS 는 최대 1개라
     * (V7 의 uk_matching_intent_sessions_active) Optional 로 받는다.
     */
    Optional<MatchingIntentSession> findByUserIdAndStatus(Long userId, IntentSessionStatus status);
}
