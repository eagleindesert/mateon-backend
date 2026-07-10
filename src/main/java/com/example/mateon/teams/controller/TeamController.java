package com.example.mateon.teams.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.teams.dto.request.TeamApplicationRequestDTO;
import com.example.mateon.teams.dto.request.TeamRequestDTO;
import com.example.mateon.teams.dto.response.TeamApplicationResponseDTO;
import com.example.mateon.teams.dto.response.TeamDetailResponseDTO;
import com.example.mateon.teams.dto.response.TeamResponseDTO;
import com.example.mateon.teams.service.TeamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    // 1. 팀 모집글 조회 (통합)
    @GetMapping
    public ResponseEntity<ApiResponse<List<TeamResponseDTO>>> getTeams(
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") boolean myPosts,
            Authentication authentication
    ) {
        Long userId = (authentication != null) ? Long.valueOf(authentication.getName()) : null;
        List<TeamResponseDTO> responses = teamService.getTeams(eventId, category, myPosts, userId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
    // 1-2. 팀 모집글 상세 조회 (단건)
    // 리더 여부(isLeader)와 지원 여부(hasApplied)가 포함된 DTO 반환
    @GetMapping("/{teamId}")
    public ResponseEntity<ApiResponse<TeamDetailResponseDTO>> getTeam(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        Long userId = (authentication != null) ? Long.valueOf(authentication.getName()) : null;
        TeamDetailResponseDTO response = teamService.getTeamDetail(teamId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }



    // 2. 팀 모집글 작성
    @PostMapping
    public ResponseEntity<ApiResponse<TeamResponseDTO>> createTeam(
            @Valid @RequestBody TeamRequestDTO request,
            Authentication authentication
    ) {
        TeamResponseDTO response = teamService.createTeam(request, Long.valueOf(authentication.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // 3. 팀 모집글 수정
    @PutMapping("/{teamId}")
    public ResponseEntity<ApiResponse<TeamResponseDTO>> updateTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody TeamRequestDTO request,
            Authentication authentication
    ) {
        TeamResponseDTO response = teamService.updateTeam(teamId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 4. 팀 모집글 삭제
    @DeleteMapping("/{teamId}")
    public ResponseEntity<ApiResponse<String>> deleteTeam(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        teamService.deleteTeam(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("삭제되었습니다."));
    }

    // 5. 특정 팀에 지원하기
    @PostMapping("/{teamId}/apply")
    public ResponseEntity<ApiResponse<String>> applyToTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody TeamApplicationRequestDTO request,
            Authentication authentication
    ) {
        teamService.applyToTeam(teamId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("지원이 완료되었습니다."));
    }

    // 6. 내가 쓴 지원서 목록 보기
    @GetMapping("/applications/me")
    public ResponseEntity<ApiResponse<List<TeamApplicationResponseDTO>>> getMyApplications(
            Authentication authentication
    ) {
        List<TeamApplicationResponseDTO> responses = teamService.getMyApplications(Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    // 7. (팀장용) 내 팀에 온 지원서 목록 보기
    @GetMapping("/{teamId}/applications")
    public ResponseEntity<ApiResponse<List<TeamApplicationResponseDTO>>> getApplicationsForTeam(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        List<TeamApplicationResponseDTO> responses = teamService.getApplicationsForMyTeam(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    // 8. (팀장용) 지원서 승인/거절 처리
    @PatchMapping("/applications/{applicationId}")
    public ResponseEntity<ApiResponse<String>> processApplication(
            @PathVariable Long applicationId,
            @RequestParam boolean isApproved,
            Authentication authentication
    ) {
        teamService.processApplication(applicationId, isApproved, Long.valueOf(authentication.getName()));
        String message = isApproved ? "승인되었습니다." : "거절되었습니다.";
        return ResponseEntity.ok(ApiResponse.success(message));
    }
    // 9. 지원서 수정 (지원자용)
    @PutMapping("/applications/{applicationId}")
    public ResponseEntity<ApiResponse<String>> updateApplication(
            @PathVariable Long applicationId,
            @Valid @RequestBody TeamApplicationRequestDTO request,
            Authentication authentication
    ) {
        teamService.updateApplication(applicationId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("지원서가 수정되었습니다."));
    }

    // 10. 지원 취소 (지원자용)
    @DeleteMapping("/applications/{applicationId}")
    public ResponseEntity<ApiResponse<String>> cancelApplication(
            @PathVariable Long applicationId,
            Authentication authentication
    ) {
        teamService.cancelApplication(applicationId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("지원이 취소되었습니다."));
    }
    // 11. [NEW] 지원서 상세 조회 (팀장 OR 지원자 본인만 가능)
    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<ApiResponse<TeamApplicationResponseDTO>> getApplicationDetail(
            @PathVariable Long applicationId,
            Authentication authentication
    ) {
        TeamApplicationResponseDTO response = teamService.getApplicationDetail(applicationId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}