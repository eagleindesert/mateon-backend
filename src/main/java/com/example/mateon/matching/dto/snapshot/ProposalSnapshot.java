package com.example.mateon.matching.dto.snapshot;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 제안 조립에 필요한 모든 것. 조회 TX 가 커밋된 뒤 TX 밖에서 FastAPI 를 호출할 때 넘긴다.
 *
 * <p>{@link ReasonSnapshot} 과 같은 규약으로 <b>엔티티를 하나도 담지 않는다</b> — 요약 조립을
 * 조회 TX 안에서 끝내고 결과 문자열만 들고 나온다.
 *
 * <p>캐시 필드가 없는 게 ReasonSnapshot 과의 차이다. 초안은 저장하지 않으므로 캐시할 곳도 없고,
 * 같은 상대에게 다시 눌렀을 때 문구가 달라지는 건 여기선 자연스러운 동작이다(마음에 안 들어
 * 다시 뽑는 것).
 */
@Getter
@RequiredArgsConstructor
public class ProposalSnapshot {

    /** 방향과 무관하게 "그 유저". 유저→팀이면 요청자, 팀→유저면 제안 대상이다. */
    private final Long userId;

    /** 방향과 무관하게 "그 팀". */
    private final Long teamId;

    /** 팀이 참여 중인 활동. 자율 프로젝트 팀은 null 이 정상이다. */
    private final Long contestId;

    /** matching_intent_slots.id — 위 userId 의 의도 슬롯. */
    private final Long intentId;

    /** 추천 단계에서 나온 적합도. 재계산하지 않고 그대로 실어 보낸다. */
    private final Double synergyScore;

    /** 선택된 상대 쪽 요약. */
    private final String candidateSummary;

    /** 질의 주체 쪽 요약. */
    private final String targetSummary;
}
