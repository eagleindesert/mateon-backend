package com.example.mateon.matching.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.matching.dto.request.RecommendationReasonRequestDTO;
import com.example.mateon.matching.dto.request.UserReasonRequestDTO;
import com.example.mateon.matching.dto.response.RecommendationReasonResponseDTO;
import com.example.mateon.matching.dto.response.TeamRecommendationResponseDTO;
import com.example.mateon.matching.dto.response.UserRecommendationResponseDTO;
import com.example.mateon.matching.service.RecommendationReasonService;
import com.example.mateon.matching.service.RecommendationService;
import com.example.mateon.matching.service.TeamToUserRecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 매칭 추천 API. 점수 계산과 근거 문구 생성은 별도 FastAPI 서버가 한다.
 *
 * <p>선행 조건: 의도 추출(/api/matching/intents)이 완료돼 있어야 한다. 아직이면 400
 * MATCHING_INTENT_REQUIRED 로 응답한다.
 */
@RestController
@RequestMapping("/api/matching/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final TeamToUserRecommendationService teamToUserRecommendationService;
    private final RecommendationReasonService recommendationReasonService;

    /**
     * 지원할 만한 모집 중인 팀을 적합도 순으로 추천한다.
     * 내가 팀장인 팀과 이미 지원한 팀은 제외된다.
     *
     * <p>추천할 팀이 없으면 빈 배열이다 — "아직 후보가 없음"은 정상 상태라 404 가 아니다.
     *
     * @param eventId 지정 시 해당 활동의 팀만. 생략하면 전체 모집 중인 팀.
     * @param limit   내려받을 상위 건수. 단, AI 서버가 상위 10건까지만 점수를 매겨 돌려주므로
     *                10 을 넘겨도 실제로는 최대 10건이다 (후보를 아무리 많이 보내도 동일).
     *                채우지 못하면 RecommendationService 가 warn 을 남긴다.
     */
    @GetMapping("/user-to-team")
    public ResponseEntity<ApiResponse<List<TeamRecommendationResponseDTO>>> recommendTeams(
            @RequestParam(required = false) Long eventId,
            @RequestParam(defaultValue = "10") int limit,
            Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                recommendationService.recommendTeams(userId, eventId, limit)));
    }

    /**
     * 역제안 — 이 팀에 맞는 유저를 적합도 순으로 추천한다. 팀장만 호출할 수 있다.
     *
     * <p>여기서 나온 유저에게 POST /api/teams/{teamId}/offers 로 제안을 보내면, 그 유저가
     * 수락 여부를 결정한다 (지원과 승인 주체가 반대인 경로).
     *
     * <p>후보는 매칭 의도 추출을 마친 유저뿐이다. 이미 이 팀의 팀원/지원자이거나 이 팀에게서
     * 제안을 받은 적이 있는 유저는 제외된다. 추천할 유저가 없으면 빈 배열이다 (404 아님).
     *
     * @param teamId 추천을 받을 팀. 요청자가 이 팀의 팀장이 아니면 403.
     * @param limit  내려받을 상위 건수. user-to-team 과 마찬가지로 AI 가 점수를 매겨 준
     *               건수를 넘을 수 없다.
     */
    @GetMapping("/team-to-user")
    public ResponseEntity<ApiResponse<List<UserRecommendationResponseDTO>>> recommendUsers(
            @RequestParam Long teamId,
            @RequestParam(defaultValue = "10") int limit,
            Authentication authentication
    ) {
        Long leaderUserId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                teamToUserRecommendationService.recommendUsers(teamId, leaderUserId, limit)));
    }

    // ── 상세 이유 (lazy) ─────────────────────────────────────────────────────
    //
    // 추천 목록의 label 은 짧은 한 줄이고, 여기서 얻는 reason 은 긴 설명이다. 사용자가 카드를
    // 선택한 시점에만 부른다 — 목록의 모든 후보에 대해 미리 만들면 LLM 호출이 후보 수만큼 나간다.
    //
    // 조회인데 GET 이 아닌 이유: AI 호출을 유발하고 결과를 캐시하는 부수효과가 있다.

    /**
     * 추천받은 팀 하나에 대해 "왜 이 팀인가"를 생성한다.
     *
     * <p>같은 팀을 다시 요청하면 처음 생성한 문장을 그대로 돌려준다 (AI 재호출 없음). 따라서
     * 문구는 한 번 정해지면 바뀌지 않는다.
     *
     * <p>추천에 뜬 적 없는 팀이면 404 RECOMMENDATION_NOT_FOUND 다. 먼저
     * GET /user-to-team 을 호출해 목록을 받아야 한다.
     */
    @PostMapping("/reason/user-to-team")
    public ResponseEntity<ApiResponse<RecommendationReasonResponseDTO>> explainTeam(
            @Valid @RequestBody RecommendationReasonRequestDTO request,
            Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        String reason = recommendationReasonService.explainTeam(userId, request.getTeamId());
        return ResponseEntity.ok(ApiResponse.success(
                new RecommendationReasonResponseDTO(reason)));
    }

    /**
     * 역제안으로 추천받은 유저 한 명에 대해 "왜 이 사람인가"를 생성한다. 팀장만 호출할 수 있다.
     *
     * <p>캐시 규칙과 404 조건은 유저→팀 방향과 같다 (선행 호출은 GET /team-to-user).
     *
     * @param request teamId 의 팀장이 아니면 403.
     */
    @PostMapping("/reason/team-to-user")
    public ResponseEntity<ApiResponse<RecommendationReasonResponseDTO>> explainUser(
            @Valid @RequestBody UserReasonRequestDTO request,
            Authentication authentication
    ) {
        Long leaderUserId = Long.valueOf(authentication.getName());
        String reason = recommendationReasonService.explainUser(
                request.getTeamId(), request.getUserId(), leaderUserId);
        return ResponseEntity.ok(ApiResponse.success(
                new RecommendationReasonResponseDTO(reason)));
    }
}
