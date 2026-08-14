package com.example.mateon.teams.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 팀원 평가 일괄 제출. 한 번에 다 내는 이유는 부분 제출을 허용하면 "누구를 아직 안 냈는지"가
 * 화면과 서버에서 갈라지고, 마감 직전 일부만 낸 상태가 늘어나기 때문이다.
 */
@Schema(description = "팀원 평가 일괄 제출. **부분 제출은 없다** — 하나라도 실패하면 전부 롤백되고, "
        + "제출 후 수정·삭제도 없다.")
@Getter
@Setter
@NoArgsConstructor
public class TeamReviewSubmitRequestDTO {

    @Schema(description = "평가 목록. GET /{teamId}/reviews/targets 로 받은 대상 전원을 한 번에 담는다.")
    @NotEmpty(message = "평가 내용이 비어 있습니다.")
    @Valid
    private List<Item> reviews;

    @Schema(name = "TeamReviewItem", description = "평가 한 건")
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Item {

        @Schema(description = "평가 대상 팀원의 userId. 자기 자신을 넣으면 400 CANNOT_REVIEW_SELF.")
        @NotNull
        private Long revieweeId;

        @Schema(description = "1~5 점. 협업 온도 계산에 반영된다.")
        @NotNull
        @Min(value = 1, message = "평가 점수는 1~5 사이여야 합니다.")
        @Max(value = 5, message = "평가 점수는 1~5 사이여야 합니다.")
        private Integer rating;
    }
}
