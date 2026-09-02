package com.example.mateon.events.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 공모전 유사도 지도. FastAPI /contests/similarity-map 응답을 프론트용 camelCase 로 옮긴 것이다.
 *
 * <p>
 * 벡터는 포함하지 않는다. id 는 우리 events.id (Long) 이다.
 */
@Schema(description = "기준 공모전과 후보들의 유사도·방사형 그래프 좌표")
@Getter
@AllArgsConstructor
public class ContestSimilarityMapResponseDTO {

    @Schema(description = "그래프 중심이 되는 기준 공모전. 임베딩 벡터는 없다.")
    private final Query query;

    @Schema(description = "유사도 내림차순 후보. 최대 topN 개. 후보가 없으면 빈 배열이다.")
    private final List<Point> points;

    @Schema(description = "이번 응답의 기준 반지름 최댓값. 축 스케일에 쓴다.")
    private final double maxRadius;

    @Schema(description = "이번 응답의 기준 반지름 최솟값.")
    private final double minRadius;

    @Schema(description = "점 겹침을 줄이는 최대 흔들림. 원점 거리는 radius 를 중심으로 이만큼까지 벗어날 수 있다.")
    private final double radialJitter;

    @Schema(description = "상위 10/30/60/90% 지점의 참고선. 후보가 없으면 빈 배열이다.")
    private final List<ReferenceRing> referenceRings;

    @Schema(description = "이번 요청에서 AI 가 받은 후보 수(topN 절단 후). DB 전체 공모전 수가 아니다.")
    private final int candidatePoolTotal;

    @Schema(description = "그래프 중심이 되는 기준 공모전")
    @Getter
    @AllArgsConstructor
    public static class Query {

        @Schema(description = "기준 활동 ID. 우리 events.id 다.")
        private final Long id;

        @Schema(description = "제목")
        private final String title;

        @Schema(description = "주최. 없으면 null.")
        private final String organizer;

        @Schema(description = "활동 분류. CONTEST / EXTERNAL / SCHOOL / ETC. 없으면 null.")
        private final String category;

        @Schema(description = "분야 코드(EDUCATION 등). 없으면 null.")
        private final String field;

        @Schema(description = "분야의 한글 표기. field 가 null 이면 null.")
        private final String fieldLabel;

        @Schema(description = "상세 URL. 없으면 null.")
        private final String detailUrl;
    }

    @Schema(description = "유사도 내림차순으로 정렬된 후보 한 점")
    @Getter
    @AllArgsConstructor
    public static class Point {

        @Schema(description = "후보 활동 ID. 우리 events.id 다.")
        private final Long id;

        @Schema(description = "제목")
        private final String title;

        @Schema(description = "주최. 없으면 null.")
        private final String organizer;

        @Schema(description = "활동 분류. CONTEST / EXTERNAL / SCHOOL / ETC. 없으면 null.")
        private final String category;

        @Schema(description = "분야 코드(EDUCATION 등). 없으면 null.")
        private final String field;

        @Schema(description = "분야의 한글 표기. field 가 null 이면 null.")
        private final String fieldLabel;

        @Schema(description = "상세 URL. 없으면 null.")
        private final String detailUrl;

        @Schema(description = "코사인 유사도. 이론적 범위 -1.0~1.0.")
        private final double similarity;

        @Schema(description = "이번 후보군 안 순위 백분위. 0.0 이 가장 유사, 1.0 이 가장 덜 유사. 후보 1개면 0.0.")
        private final double rankPercentile;

        @Schema(description = "순위 백분위로 계산한 기준 반지름. 가장 유사하면 minRadius, 가장 덜 유사하면 maxRadius.")
        private final double radius;

        @Schema(description = "그래프에 바로 쓸 x 좌표. 원점 거리는 radius 를 중심으로 최대 radialJitter 만큼 흔들릴 수 있다.")
        private final double x;

        @Schema(description = "그래프에 바로 쓸 y 좌표. x 와 같다.")
        private final double y;
    }

    @Schema(description = "상위 10/30/60/90% 지점의 참고선")
    @Getter
    @AllArgsConstructor
    public static class ReferenceRing {

        @Schema(description = "백분위. 0.1 / 0.3 / 0.6 / 0.9.")
        private final double percentile;

        @Schema(description = "그 백분위 지점의 유사도.")
        private final double similarityAtPercentile;

        @Schema(description = "그 백분위 지점의 기준 반지름.")
        private final double radius;
    }
}
