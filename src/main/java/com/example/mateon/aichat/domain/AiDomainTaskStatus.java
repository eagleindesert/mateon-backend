package com.example.mateon.aichat.domain;

/**
 * 도메인 작업이 살아 있는지.
 *
 * <p>
 * <b>"왜 끝났는지"는 여기 없다</b> — {@link TaskCloseReason} 이 따로 갖는다. 한 컬럼에
 * 겹쳐 담으면 "살아 있나"를 물을 때마다 종료 사유들을 나열해 빼야 한다(V7 의
 * {@code status <> 'IN_PROGRESS'} 가 그랬다).
 *
 * <p>
 * 사용자당 도메인당 ACTIVE 는 최대 1건이다 — V31 의 부분 유니크 인덱스
 * (uk_ai_domain_tasks_active)가 DB 레벨에서 보장한다.
 */
public enum AiDomainTaskStatus {

    /**
     * 진행 중. 이 도메인으로 들어온 발화는 이 작업에 쌓인다.
     */
    ACTIVE,
    /**
     * 끝남. 같은 도메인으로 다시 말하면 새 작업이 열린다.
     */
    CLOSED
}
