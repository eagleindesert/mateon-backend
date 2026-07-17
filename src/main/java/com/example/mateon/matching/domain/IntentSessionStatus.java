package com.example.mateon.matching.domain;

/**
 * 의도 추출 대화 세션의 상태.
 * IN_PROGRESS 는 사용자당 최대 1개 (V7 의 부분 유니크 인덱스가 보장).
 */
public enum IntentSessionStatus {
    /** 재질문이 진행 중. 아직 missing_fields 가 남아있다. */
    IN_PROGRESS,
    /** 슬롯이 다 채워져 MatchingIntentSlot 이 생성됨. */
    COMPLETED,
    /** 사용자가 /session/restart 로 버림. */
    ABANDONED,
    /** session-ttl 을 넘겨 방치됨 (지연 만료). */
    EXPIRED
}
