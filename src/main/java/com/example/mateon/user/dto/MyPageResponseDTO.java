package com.example.mateon.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    // 2. 드림이 리포트 (AI 분석 결과)
    private AiAnalysisDTO dreamyReport;

    // 3. 참여한 활동 (승인된 것만)
    private List<ActivitySummaryDTO> participatedActivities;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiAnalysisDTO {
        private int score; // 적합도 점수 (0~100)
        private String strength; // 강점
        private String weakness; // 보완점
        private String recommendedAction; // 추천 활동
    }

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