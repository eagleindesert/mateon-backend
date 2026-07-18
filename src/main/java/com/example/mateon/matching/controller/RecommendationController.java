package com.example.mateon.matching.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.matching.dto.response.TeamRecommendationResponseDTO;
import com.example.mateon.matching.service.RecommendationService;
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

    /**
     * 지원할 만한 모집 중인 팀을 적합도 순으로 추천한다.
     * 내가 팀장인 팀과 이미 지원한 팀은 제외된다.
     *
     * <p>추천할 팀이 없으면 빈 배열이다 — "아직 후보가 없음"은 정상 상태라 404 가 아니다.
     *
     * @param eventId 지정 시 해당 활동의 팀만. 생략하면 전체 모집 중인 팀.
     * @param limit   내려받을 상위 건수.
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
}
