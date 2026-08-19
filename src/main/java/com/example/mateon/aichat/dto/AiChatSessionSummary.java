package com.example.mateon.aichat.dto;

import java.time.LocalDateTime;

/**
 * 사이드바 한 줄. 스레드 목록 조회가 이걸로 내려온다.
 *
 * <p>엔티티를 그대로 내보내지 않는 이유는 {@code lastMessage} 때문이다 — 목록 N 건에 대해
 * 마지막 메시지를 각각 조회하면 N+1 이 된다. JPQL 상관 서브쿼리로 한 방에 뽑아 이 record 로
 * 받는다.
 *
 * @param lastMessage 마지막 메시지 본문. 발화가 없는 새 스레드면 null.
 */
public record AiChatSessionSummary(Long sessionId,
                                   String title,
                                   String lastMessage,
                                   LocalDateTime updatedAt) {
}
