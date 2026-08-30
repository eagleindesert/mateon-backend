package com.example.mateon.events.event;

/**
 * 활동 등록 트랜잭션에서 발행 — 커밋 후 임베딩을 계산하라는 신호.
 *
 * <p>
 * eventId 만 담는 이유: 리스너가 별도 스레드에서 활동을 fresh 조회하므로,
 * 연속 수정이 겹쳐도 항상 최신 데이터로 계산된다 (중복 호출은 멱등이라 무해).
 */
public record EventEmbeddingRefreshRequestedEvent(Long eventId) {

}
