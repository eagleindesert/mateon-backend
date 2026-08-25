package com.example.mateon.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "[폐기 예정] 마이페이지 종합 정보. GET /api/users/me 가 같은 값을 모두 준다.")
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
    @Schema(description = "협업 온도. 평가 0건이면 기준점 36.5 다.")
    private BigDecimal collaborationTemperature;
    private int collaborationReviewCount;

    // 3. 참여한 활동 (승인된 것만)
    @Schema(description = "참여한 활동. 승인되어 실제로 합류한 것만 담긴다.")
    private List<ActivitySummaryDTO> participatedActivities;

    @Schema(name = "ActivitySummary", description = "참여 활동 한 줄. 내 프로필·공개 프로필·마이페이지가 같은 형태를 쓴다.")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivitySummaryDTO {

        private Long id;
        private String title; // 활동 제목
        @Schema(description = "활동 카테고리의 한글 표기.", example = "공모전")
        private String category; // 활동 카테고리 (공모전, 대외활동, 교내 등)
    }
}
