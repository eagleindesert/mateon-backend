package com.example.mateon.teams.dto.request;

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
@Getter
@Setter
@NoArgsConstructor
public class TeamReviewSubmitRequestDTO {

    @NotEmpty(message = "평가 내용이 비어 있습니다.")
    @Valid
    private List<Item> reviews;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Item {

        @NotNull
        private Long revieweeId;

        @NotNull
        @Min(value = 1, message = "평가 점수는 1~5 사이여야 합니다.")
        @Max(value = 5, message = "평가 점수는 1~5 사이여야 합니다.")
        private Integer rating;
    }
}
