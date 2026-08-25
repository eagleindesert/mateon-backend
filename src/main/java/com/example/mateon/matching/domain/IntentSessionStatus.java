package com.example.mateon.matching.domain;

import com.example.mateon.aichat.domain.AiDomainTask;

/**
 * 의도 추출 대화의 상태. <b>저장되지 않는다</b> — 프론트 계약을 유지하기 위한 파생 값이다.
 *
 * <p>
 * V31 에서 수명 상태를 {@link AiDomainTask} 로 올리면서 이 값은 컬럼에서 사라졌다. 대신
 * {@code (status, closedReason)} 두 컬럼에서 {@link #of} 로 1:1 복원한다 — 그래서
 * {@code GET /api/matching/intents/session} 의 응답 JSON 은 한 글자도 바뀌지 않는다.
 *
 * <p>
 * 새 코드는 이걸로 분기하지 말 것. "살아 있나"는 {@code AiDomainTask.isActive()} 가 답한다.
 * 이 enum 은 바깥으로 나가는 표현일 뿐이다.
 */
public enum IntentSessionStatus {

    /**
     * 재질문이 진행 중. 아직 missing_fields 가 남아있다.
     */
    IN_PROGRESS,
    /**
     * 슬롯이 다 채워져 MatchingIntentSlot 이 생성됨.
     */
    COMPLETED,
    /**
     * 사용자가 /session/restart 로 버림.
     */
    ABANDONED,
    /**
     * session-ttl 을 넘겨 방치됨 (지연 만료).
     */
    EXPIRED;

    /**
     * 상위 작업의 두 컬럼에서 예전 네 값을 복원한다.
     */
    public static IntentSessionStatus of(AiDomainTask task) {
        if (task.isActive()) {
            return IN_PROGRESS;
        }
        return switch (task.getClosedReason()) {
            case COMPLETED ->
                COMPLETED;
            case ABANDONED ->
                ABANDONED;
            case EXPIRED ->
                EXPIRED;
        };
    }
}
