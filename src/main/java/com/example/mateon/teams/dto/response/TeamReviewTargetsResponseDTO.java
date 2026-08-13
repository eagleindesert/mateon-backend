package com.example.mateon.teams.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내가 이 팀에서 평가해야 할 대상 목록.
 *
 * <p>여기에 다른 사람이 남긴 평가는 절대 담기지 않는다 — alreadyReviewed 는 '내가' 냈는지일 뿐이다.
 */
@Schema(description = "내가 이 팀에서 평가해야 할 대상 목록. **다른 사람이 남긴 평가는 절대 담기지 않는다.**")
@Getter
@AllArgsConstructor
public class TeamReviewTargetsResponseDTO {

    private Long teamId;
    private String teamTitle;
    @Schema(description = "팀장이 활동을 종료한 시각.")
    private LocalDateTime endedAt;
    /** 이 시각이 지나면 제출이 거부된다. */
    @Schema(description = "평가 마감 시각. 이 시각이 지나면 제출이 400 REVIEW_PERIOD_EXPIRED 로 거부된다.")
    private LocalDateTime reviewDeadline;
    @Schema(description = "평가 대상 팀원 명단. 자기 자신은 빠져 있다.")
    private List<Target> targets;

    @Schema(name = "TeamReviewTarget", description = "평가 대상 한 명")
    @Getter
    @AllArgsConstructor
    public static class Target {
        private Long userId;
        private String name;
        private String major;
        /** 내가 이미 이 사람을 평가했는지. 제출은 1회뿐이다. */
        @Schema(description = "**내가** 이미 이 사람을 평가했는지. 제출은 1회뿐이라 true 면 다시 낼 수 없다.")
        private boolean alreadyReviewed;
    }
}
