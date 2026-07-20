package com.example.mateon.matching.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.matching.dto.response.TeamRecommendationResponseDTO;
import com.example.mateon.matching.dto.response.UserRecommendationResponseDTO;
import com.example.mateon.matching.service.RecommendationService;
import com.example.mateon.matching.service.TeamToUserRecommendationService;
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
}
