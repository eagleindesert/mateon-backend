package com.example.mateon.teams.event;

/**
 * 팀 생성/수정 트랜잭션에서 발행 — 커밋 후 임베딩을 재계산하라는 신호.
 *
 * <p>teamId 만 담는 이유: 리스너가 별도 스레드에서 팀을 fresh 조회하므로,
 * 연속 수정이 겹쳐도 항상 최신 데이터로 계산된다 (중복 호출은 멱등이라 무해).
 */
public record TeamEmbeddingRefreshRequestedEvent(Long teamId) {
}
