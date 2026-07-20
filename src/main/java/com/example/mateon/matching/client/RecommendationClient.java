package com.example.mateon.matching.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 별도 FastAPI AI 서버의 추천 엔드포인트들을 호출한다.
 *
 * <ul>
 *   <li>POST /recommendations/user-to-team — 유저가 팀을 찾는다 (지원)</li>
 *   <li>POST /recommendations/team-to-user — 팀이 유저를 찾는다 (역제안)</li>
 *   <li>POST /recommendations/reason — 선택된 한 쌍의 상세 이유 (lazy)</li>
 * </ul>
 *
 * <p>셋을 한 빈에 둔 이유: 엔드포인트와 스키마만 다르고 "추천"이라는 같은 개념을 다룬다.
 * 인증 헤더와 실패 처리는 {@link AiCallTemplate} 이 담당한다 — 클라이언트를 복사하면
 * 401/422 진단 로그가 여러 벌이 되고 한쪽만 고쳐지는 사고가 나기 때문이다.
 *
 * <p>AI 서버는 stateless — 점수만 계산해 돌려주고 저장하지 않는다. 후보를 고르고(모집 중 여부,
 * 본인 팀 제외 등) 결과를 저장하는 건 호출자 몫이다.
 *
 * <p>예외는 IntentExtractionClient 와 같은 규약으로 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationClient {

    private static final String USER_TO_TEAM_PATH = "/recommendations/user-to-team";
    private static final String TEAM_TO_USER_PATH = "/recommendations/team-to-user";
    private static final String REASON_PATH = "/recommendations/reason";

    private final AiCallTemplate ai;

    /** 유저 한 명에게 맞는 팀들을 점수화한다. */
    public RecommendationResponse userToTeam(UserToTeamRecommendationRequest request) {
        RecommendationResponse body =
                ai.post(USER_TO_TEAM_PATH, request, RecommendationResponse.class);
        if (body.getRecommendations() == null) {
            log.warn("AI {} 응답에 recommendations 가 없습니다", USER_TO_TEAM_PATH);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
        return body;
    }

    /** 팀 하나에 맞는 유저들을 점수화한다 (역제안). */
    public RecommendationResponse teamToUser(TeamToUserRecommendationRequest request) {
        RecommendationResponse body =
                ai.post(TEAM_TO_USER_PATH, request, RecommendationResponse.class);
        if (body.getRecommendations() == null) {
            log.warn("AI {} 응답에 recommendations 가 없습니다", TEAM_TO_USER_PATH);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
        return body;
    }

    /**
     * 추천된 한 쌍의 상세 이유를 생성한다 (사용자가 카드를 선택한 시점에 lazy 호출).
     *
     * <p>점수를 다시 계산하지 않는다 — 추천 단계에서 나온 값을 score_context 에 서술로 실어
     * 보낼 뿐이다.
     *
     * @return 비어있지 않은 이유 문자열. AI 가 빈 값을 주면 예외 (호출자가 빈 이유를 캐시해
     *         영구히 남기는 걸 막는다).
     */
    public String reason(RecommendationReasonRequest request) {
        RecommendationReasonResponse body =
                ai.post(REASON_PATH, request, RecommendationReasonResponse.class);

        if (body.getReason() == null || body.getReason().isBlank()) {
            log.warn("AI {} 가 빈 reason 을 반환했습니다", REASON_PATH);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
        return body.getReason();
    }
}
