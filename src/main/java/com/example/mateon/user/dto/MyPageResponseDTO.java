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
    // 프로필 사진 공개 URL. 사진이 없거나 업로드가 아직 안 끝났으면 null.
    private String profileImageUrl;

    // 2. 협업 온도. 평가 건수와 무관하게 항상 값이 있고, 0건이면 기준점 36.5 다.
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