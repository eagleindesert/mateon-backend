package com.example.mateon.matching.controller;

import com.example.mateon.common.dto.BaseResponse;
import com.example.mateon.matching.dto.request.RecommendationReasonRequestDTO;
import com.example.mateon.matching.dto.request.UserReasonRequestDTO;
import com.example.mateon.matching.dto.response.RecommendationReasonResponseDTO;
import com.example.mateon.matching.dto.response.TeamRecommendationResponseDTO;
import com.example.mateon.matching.dto.response.UserRecommendationResponseDTO;
import com.example.mateon.matching.service.RecommendationReasonService;
import com.example.mateon.matching.service.RecommendationService;
import com.example.mateon.matching.service.TeamToUserRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "추천", description = "유저→팀, 팀→유저 양방향 AI 추천")
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
    @Operation(summary = "지원할 만한 팀 추천 (유저→팀)",
            description = """
                          모집 중인 팀을 적합도 순으로 내려준다. 내가 팀장인 팀과 이미 지원한 팀은
                          빠진다.

                          **선행 조건:** 매칭 의도 추출(`/api/matching/intents`)이 끝나 있어야 한다.
                          아직이면 400 MATCHING_INTENT_REQUIRED 다.

                          추천할 팀이 없으면 빈 배열이다 — "아직 후보가 없음"은 정상 상태라 404 가
                          아니다.

                          각 항목의 label 은 짧은 한 줄 요약이다. 긴 설명이 필요하면 카드를 고른
                          시점에 `POST /reason/user-to-team` 을 부른다.""")
    @ApiResponse(responseCode = "400",
            description = "MATCHING_INTENT_REQUIRED — 먼저 매칭 의도 추출을 완료해주세요.")
    @ApiResponse(responseCode = "502",
            description = "AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다.")
    @ApiResponse(responseCode = "503",
            description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다. 잠시 후 재시도하면 된다.")
    @Parameter(name = "eventId", description = "지정하면 그 활동에 연결된 팀만 추천한다. 생략하면 모집 중인 팀 전체가 후보다.")
    @Parameter(name = "limit",
            description = "내려받을 상위 건수. 다만 AI 서버가 상위 10건까지만 점수를 매기므로 "
                    + "10 을 넘겨도 실제로는 최대 10건이다.")
    @GetMapping("/user-to-team")
    public ResponseEntity<BaseResponse<List<TeamRecommendationResponseDTO>>> recommendTeams(
            @RequestParam(required = false) Long eventId,
            @RequestParam(defaultValue = "10") int limit,
            Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(BaseResponse.success(
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
    @Operation(summary = "(팀장용) 팀에 맞는 유저 추천 (팀→유저, 역제안)",
            description = """
                          지원과 승인 주체가 반대인 경로다. 여기서 나온 유저에게
                          `POST /api/teams/{teamId}/offers` 로 제안을 보내면 그 유저가 수락 여부를
                          결정한다.

                          후보는 **매칭 의도 추출을 마친 유저**뿐이다. 이미 이 팀의 팀원·지원자이거나
                          이 팀에게 제안을 받은 적이 있는 유저는 빠진다. 없으면 빈 배열이다(404 아님).

                          팀 임베딩은 팀 생성·수정 후 비동기로 계산된다. 아직 안 끝났으면
                          400 TEAM_EMBEDDING_NOT_READY 이므로 잠시 후 다시 부르면 된다.""")
    @ApiResponse(responseCode = "400", description = """
            TEAM_EMBEDDING_NOT_READY — 팀 정보 분석이 아직 완료되지 않았습니다. 잠시 후 재시도.
            MATCHING_INTENT_REQUIRED — 먼저 매칭 의도 추출을 완료해주세요.
            FORBIDDEN_ACCESS — 이 팀의 팀장만 호출할 수 있습니다.
            RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.""")
    @ApiResponse(responseCode = "502",
            description = "AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다.")
    @ApiResponse(responseCode = "503",
            description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다.")
    @Parameter(name = "teamId", description = "추천을 받을 팀. 요청자가 이 팀의 팀장이 아니면 400 FORBIDDEN_ACCESS.")
    @Parameter(name = "limit", description = "내려받을 상위 건수. user-to-team 과 마찬가지로 AI 가 점수를 매겨 준 건수를 넘을 수 없다.")
    @GetMapping("/team-to-user")
    public ResponseEntity<BaseResponse<List<UserRecommendationResponseDTO>>> recommendUsers(
            @RequestParam Long teamId,
            @RequestParam(defaultValue = "10") int limit,
            Authentication authentication
    ) {
        Long leaderUserId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(BaseResponse.success(
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
    @Operation(summary = "추천받은 팀의 상세 이유 생성",
            description = """
                          "왜 이 팀인가"를 문장으로 만들어 준다. 목록의 짧은 label 과 달리 긴 설명이라,
                          사용자가 카드를 고른 시점에만 부른다(후보 전부에 미리 만들면 LLM 호출이
                          후보 수만큼 나간다).

                          **같은 팀을 다시 요청하면 처음 만든 문장을 그대로 돌려준다**(AI 재호출 없음).
                          한 번 정해지면 문구가 바뀌지 않는다.

                          조회처럼 보이는데 POST 인 이유가 이것이다 — AI 호출과 캐시 저장이라는
                          부수효과가 있다.

                          **선행 호출:** `GET /user-to-team`. 추천에 뜬 적 없는 팀이면
                          404 RECOMMENDATION_NOT_FOUND 다.""")
    @ApiResponse(responseCode = "400", description = """
            MATCHING_INTENT_REQUIRED — 먼저 매칭 의도 추출을 완료해주세요.
            RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.""")
    @ApiResponse(responseCode = "404",
            description = "RECOMMENDATION_NOT_FOUND — 추천 이력을 찾을 수 없습니다. 먼저 GET /user-to-team 을 부른다.")
    @ApiResponse(responseCode = "502",
            description = "AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다.")
    @ApiResponse(responseCode = "503",
            description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다.")
    @PostMapping("/reason/user-to-team")
    public ResponseEntity<BaseResponse<RecommendationReasonResponseDTO>> explainTeam(
            @Valid @RequestBody RecommendationReasonRequestDTO request,
            Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        String reason = recommendationReasonService.explainTeam(userId, request.getTeamId());
        return ResponseEntity.ok(BaseResponse.success(
                new RecommendationReasonResponseDTO(reason)));
    }

    /**
     * 역제안으로 추천받은 유저 한 명에 대해 "왜 이 사람인가"를 생성한다. 팀장만 호출할 수 있다.
     *
     * <p>캐시 규칙과 404 조건은 유저→팀 방향과 같다 (선행 호출은 GET /team-to-user).
     *
     * @param request teamId 의 팀장이 아니면 403.
     */
    @Operation(summary = "(팀장용) 역제안으로 추천받은 유저의 상세 이유 생성",
            description = """
                          "왜 이 사람인가"를 문장으로 만들어 준다. 캐시 규칙(한 번 만들면 고정)과
                          404 조건은 유저→팀 방향과 같다.

                          **선행 호출:** `GET /team-to-user`.""")
    @ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 이 팀의 팀장만 호출할 수 있습니다.
            MATCHING_INTENT_REQUIRED — 상대 유저의 매칭 의도 정보가 없습니다.
            RESOURCE_NOT_FOUND — 팀 또는 유저를 찾을 수 없습니다.""")
    @ApiResponse(responseCode = "404",
            description = "RECOMMENDATION_NOT_FOUND — 추천 이력을 찾을 수 없습니다. 먼저 GET /team-to-user 를 부른다.")
    @ApiResponse(responseCode = "502",
            description = "AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다.")
    @ApiResponse(responseCode = "503",
            description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다.")
    @PostMapping("/reason/team-to-user")
    public ResponseEntity<BaseResponse<RecommendationReasonResponseDTO>> explainUser(
            @Valid @RequestBody UserReasonRequestDTO request,
            Authentication authentication
    ) {
        Long leaderUserId = Long.valueOf(authentication.getName());
        String reason = recommendationReasonService.explainUser(
                request.getTeamId(), request.getUserId(), leaderUserId);
        return ResponseEntity.ok(BaseResponse.success(
                new RecommendationReasonResponseDTO(reason)));
    }
}
