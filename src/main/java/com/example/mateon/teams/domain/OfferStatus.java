package com.example.mateon.teams.domain;

/**
 * 역제안의 상태. ApplicationStatus 와 값이 겹치지만 별도 enum 인 이유는 CANCELED 때문이다 —
 * 지원서는 취소가 곧 삭제지만, 제안은 팀장이 보낸 기록이라 남겨야 한다.
 */
public enum OfferStatus {

    /** 유저의 응답 대기 중. 팀장이 취소할 수 있는 유일한 상태. */
    PENDING,

    /** 유저가 수락 → 그 즉시 팀원이 된 상태. */
    ACCEPTED,

    /** 유저가 거절. */
    REJECTED,

    /** 팀장이 회수. */
    CANCELED
}
