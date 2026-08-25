package com.example.mateon.aichat.domain;

/**
 * 도메인 작업이 끝난 이유. {@link AiDomainTaskStatus#CLOSED} 일 때만 채워진다.
 *
 * <p>
 * 도메인이 늘어도 이 세 가지면 충분하다 — 자기 일을 마쳤거나, 사용자가 버렸거나, 방치됐거나.
 * "슬롯이 다 찼다" 같은 도메인 고유의 의미는 각 도메인 테이블이 갖는다.
 */
public enum TaskCloseReason {

    /**
     * 도메인이 자기 일을 마쳤다 (매칭이면 슬롯이 다 채워짐).
     */
    COMPLETED,
    /**
     * 사용자가 버렸다 (매칭이면 /session/restart).
     */
    ABANDONED,
    /**
     * ai.session-ttl 을 넘겨 방치됐다 (지연 만료).
     */
    EXPIRED
}
