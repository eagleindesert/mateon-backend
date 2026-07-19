package com.example.mateon.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyPageResponseDTO {
    // 1. 기본 프로필 정보
    private String name;
    private String college; // 단과대
    private String major;   // 학과
    private String grade;   // 학년
    private String interestJobPrimary; // 희망 직무 (Primary)
    private String school; // 학교
    private String campus; // 캠퍼스
    private boolean schoolVerified; // 학교(재학생) 인증 여부

    // 2. 협업 온도. 받은 평가가 2건 미만이면 null 이다 (비공개) — 프론트는 "평가 준비 중"으로 표시한다.
    //    비공개인 이유는 통계가 아니라 익명성이다: 2인 팀에서 평가 1건이면 누가 줬는지 자명하다.
    private BigDecimal collaborationTemperature;
    private int collaborationReviewCount;

    // 3. 참여한 활동 (승인된 것만)
    private List<ActivitySummaryDTO> participatedActivities;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivitySummaryDTO {
        private Long id;
        private String title; // 활동 제목
        private String category; // 활동 카테고리 (공모전, 대외활동, 교내 등)
    }
}