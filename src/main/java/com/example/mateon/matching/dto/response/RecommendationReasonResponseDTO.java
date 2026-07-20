package com.example.mateon.matching.dto.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 추천 상세 이유 1건. 두 방향이 같은 모양으로 나간다.
 *
 * <p>필드가 reason 하나뿐이라 문자열을 그냥 내려보내도 되지만 객체로 감싼다 — 나중에 생성 시각
 * 같은 걸 얹을 때 프론트 파싱을 깨지 않기 위해서다.
 *
 * <p>캐시에서 나온 값인지 방금 만든 값인지는 알려주지 않는다. 프론트가 다르게 다룰 이유가 없고,
 * 노출하면 백엔드 캐시 전략에 프론트가 묶인다.
 */
@Getter
@RequiredArgsConstructor
public class RecommendationReasonResponseDTO {

    /** AI 가 만든 문장을 그대로. 백엔드는 해석하지 않는다 (label 과 같은 규약). */
    private final String reason;
}
