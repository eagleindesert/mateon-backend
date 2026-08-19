package com.example.mateon.aichat.repository;

import com.example.mateon.aichat.domain.AiConversation;
import com.example.mateon.aichat.domain.AiConversationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    /**
     * 진행 중인 대화를 찾는다. 사용자당 ACTIVE 는 최대 1개라
     * (V30 의 uk_ai_conversations_active) Optional 로 받는다.
     */
    Optional<AiConversation> findByUserIdAndStatus(Long userId, AiConversationStatus status);
}
