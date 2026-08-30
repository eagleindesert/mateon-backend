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

    private final Query query;
    private final List<Point> points;
    private final double maxRadius;
    private final double minRadius;
    private final double radialJitter;
    private final List<ReferenceRing> referenceRings;
    private final int candidatePoolTotal;

    @Schema(description = "그래프 중심이 되는 기준 공모전")
    @Getter
    @AllArgsConstructor
    public static class Query {
        private final Long id;
        private final String title;
        private final String organizer;
        private final String category;
        private final String field;
        private final String fieldLabel;
        private final String detailUrl;
    }

    @Schema(description = "유사도 내림차순으로 정렬된 후보 한 점")
    @Getter
    @AllArgsConstructor
    public static class Point {
        private final Long id;
        private final String title;
        private final String organizer;
        private final String category;
        private final String field;
        private final String fieldLabel;
        private final String detailUrl;
        private final double similarity;
        private final double rankPercentile;
        private final double radius;
        private final double x;
        private final double y;
    }

    @Schema(description = "상위 10/30/60/90% 지점의 참고선")
    @Getter
    @AllArgsConstructor
    public static class ReferenceRing {
        private final double percentile;
        private final double similarityAtPercentile;
        private final double radius;
    }
}
