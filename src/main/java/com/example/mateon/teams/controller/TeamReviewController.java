package com.example.mateon.teams.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.teams.dto.request.TeamReviewSubmitRequestDTO;
import com.example.mateon.teams.dto.response.TeamReviewTargetsResponseDTO;
import com.example.mateon.teams.service.TeamCompletionService;
import com.example.mateon.teams.service.TeamReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 팀 활동 종료와 팀원 평가(협업 온도).
 *
 * <p>TeamController 와 경로 접두사(/api/teams)를 공유하지만 클래스를 나눴다 —
 * TeamController 는 이미 11개 엔드포인트로 충분히 크고, 평가는 수명주기가 다른 별도 관심사다.
 */
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamReviewController {

    private final TeamCompletionService teamCompletionService;
    private final TeamReviewService teamReviewService;

    /** 활동 종료 (팀장만). 이 시점부터 평가가 열린다. */
    @PostMapping("/{teamId}/complete")
    public ResponseEntity<ApiResponse<Void>> completeTeam(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        teamCompletionService.completeByLeader(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** 내가 평가해야 할 팀원 목록 + 마감 시각. */
    @GetMapping("/{teamId}/reviews/targets")
    public ResponseEntity<ApiResponse<TeamReviewTargetsResponseDTO>> getReviewTargets(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        TeamReviewTargetsResponseDTO response =
                teamReviewService.getTargets(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /** 평가 일괄 제출. 제출 후 수정/삭제는 없다. */
    @PostMapping("/{teamId}/reviews")
    public ResponseEntity<ApiResponse<Void>> submitReviews(
            @PathVariable Long teamId,
            @Valid @RequestBody TeamReviewSubmitRequestDTO request,
            Authentication authentication
    ) {
        teamReviewService.submit(teamId, Long.valueOf(authentication.getName()), request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
