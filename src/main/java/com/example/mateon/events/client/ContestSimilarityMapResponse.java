package com.example.mateon.events.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * FastAPI POST /contests/similarity-map 응답 본문.
 *
 * <p>
 * @JsonNaming 을 쓰지 않고 @JsonProperty 만 쓰는 이유, @JsonIgnoreProperties 를 클래스에
 * 명시하는 이유는 {@link com.example.mateon.matching.client.intent.IntentExtractResponse} 참조.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContestSimilarityMapResponse {

    private Query query;

    private List<Point> points;

    @JsonProperty("max_radius")
    private Double maxRadius;

    @JsonProperty("min_radius")
    private Double minRadius;

    @JsonProperty("radial_jitter")
    private Double radialJitter;

    @JsonProperty("reference_rings")
    private List<ReferenceRing> referenceRings;

    @JsonProperty("candidate_pool_total")
    private Integer candidatePoolTotal;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Query {

        private String id;

        private String title;

        private String organizer;

        private String category;

        private String field;

        @JsonProperty("field_label")
        private String fieldLabel;

        @JsonProperty("detail_url")
        private String detailUrl;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Point {

        private String id;

        private String title;

        private String organizer;

        private String category;

        private String field;

        @JsonProperty("field_label")
        private String fieldLabel;

        @JsonProperty("detail_url")
        private String detailUrl;

        private Double similarity;

        @JsonProperty("rank_percentile")
        private Double rankPercentile;

        private Double radius;

        private Double x;

        private Double y;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReferenceRing {

        private Double percentile;

        @JsonProperty("similarity_at_percentile")
        private Double similarityAtPercentile;

        private Double radius;
    }
}
