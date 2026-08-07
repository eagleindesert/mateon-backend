package com.example.mateon.teams.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.teams.dto.request.TeamReviewSubmitRequestDTO;
import com.example.mateon.teams.dto.response.TeamReviewTargetsResponseDTO;
import com.example.mateon.teams.service.TeamCompletionService;
import com.example.mateon.teams.service.TeamReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "활동 종료/팀원 평가", description = "활동을 종료하고 팀원 간 협업 온도를 평가하는 경로")
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamReviewController {

    private final TeamCompletionService teamCompletionService;
    private final TeamReviewService teamReviewService;

    /** 활동 종료 (팀장만). 이 시점부터 평가가 열린다. */
    @Operation(summary = "활동 종료 (팀장만)",
            description = """
                    모집 마감(isRecruiting=false)과 다른 축이다 — 정원이 차도 활동은 그때부터 시작한다.
                    종료 시점부터 평가 기간이 열리고, 팀원들에게 평가 요청 알림이 나간다.
                    이미 종료된 팀이면 400 TEAM_ALREADY_ENDED.""")
    @PostMapping("/{teamId}/complete")
    public ResponseEntity<ApiResponse<Void>> completeTeam(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        teamCompletionService.completeByLeader(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** 내가 평가해야 할 팀원 목록 + 마감 시각. */
    @Operation(summary = "평가 대상 팀원 목록",
            description = """
                    자기 자신은 목록에서 빠지고, 이미 평가한 대상은 표시가 붙는다.
                    종료되지 않았으면 400 TEAM_NOT_ENDED, 평가 기간이 지났으면 400 REVIEW_PERIOD_EXPIRED,
                    팀원이 아니면 400 NOT_TEAM_MEMBER.""")
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
    @Operation(summary = "팀원 평가 일괄 제출",
            description = """
                    하나라도 실패하면 전부 롤백된다 — 절반만 반영되면 무엇을 다시 내야 하는지 알 수 없다.
                    제출 후 수정·삭제는 없다. 점수는 1~5.""")
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
