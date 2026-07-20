package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * FastAPI POST /recommendations/reason 요청 본문.
 *
 * <p>AI 서버는 아무것도 저장하지 않으므로 이유 생성에 필요한 컨텍스트를 전부 여기 실어 보낸다.
 * 세 값 모두 백엔드가 기존 데이터에서 조립한다 — 이 용도의 컬럼은 따로 두지 않는다
 * (조립 규칙은 {@link com.example.mateon.matching.service.RecommendationSummaryFactory}).
 *
 * <p><b>direction 필드가 없다.</b> 두 요약 텍스트만으로 LLM 이 방향과 무관하게 이유를 만들 수
 * 있다는 게 AI 명세라, user-to-team 이든 team-to-user 든 같은 엔드포인트·같은 스키마를 쓴다.
 * 백엔드는 방향에 따라 <b>후보=선택된 상대, 대상=질의 주체</b>로 자리만 맞춰 준다.
 *
 * <p>벡터는 보내지 않는다 — 점수는 추천 단계(user-to-team / team-to-user)에서 이미 계산됐고,
 * 여기서 재계산하지 않는다.
 */
@Getter
@AllArgsConstructor
public class RecommendationReasonRequest {

    /** 선택된 상대 쪽 요약. 예: "React/TypeScript 경험, 초보자, 포트폴리오용 프로젝트 희망" */
    @JsonProperty("candidate_summary")
    private final String candidateSummary;

    /** 질의 주체 쪽 요약. 예: "커머스 플랫폼, BE 1명 결핍, 주 2회 오프라인 활동" */
    @JsonProperty("target_summary")
    private final String targetSummary;

    /**
     * 점수 구성에 대한 자유 서술 (2026-07-15 변경 — 이전엔 dict 였다). AI 는 이 값을 필드별로
     * 읽지 않고 LLM 프롬프트에 그대로 이어붙이기만 하므로 형식 제약이 없다. 빈 문자열도 허용된다.
     */
    @JsonProperty("score_context")
    private final String scoreContext;
}
