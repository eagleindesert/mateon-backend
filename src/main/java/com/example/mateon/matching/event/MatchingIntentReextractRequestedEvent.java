package com.example.mateon.matching.event;

/**
 * 프로필 토글/본문이 바뀐 뒤, 이미 있는 매칭 슬롯을 같은 extract 로 다시 뽑으라는 신호.
 *
 * <p>
 * userId 만 담는 이유: 리스너가 별도 스레드에서 슬롯·접두·대화를 fresh 조회하므로,
 * 연속 수정이 겹쳐도 항상 최신 프로필로 계산된다.
 */
public record MatchingIntentReextractRequestedEvent(Long userId) {
}
