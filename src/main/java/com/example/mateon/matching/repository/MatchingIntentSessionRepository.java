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

    /**
     * 진행 중인 세션이 있는지만 본다. 게이트웨이가 매 턴 "이미 라우팅된 대화인가"를 묻는데,
     * 그 판단에 대화 이력까지 읽어 올 이유가 없어 EXISTS 한 방으로 끝낸다.
     */
    boolean existsByUserIdAndStatus(Long userId, IntentSessionStatus status);
}
