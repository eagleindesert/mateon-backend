package com.example.mateon.teams.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * FastAPI POST /internal/teams/embedding:refresh 응답 본문.
 *
 * <p>@JsonNaming 을 쓰지 않고 @JsonProperty 만 쓰는 이유, @JsonIgnoreProperties 를 클래스에
 * 명시하는 이유는 {@link com.example.mateon.matching.client.IntentExtractResponse} 참조
 * (Jackson 2/3 공존 + aiRestTemplate 자체 컨버터).
 *
 * <p>의도 추출과 달리 missing_fields 가 남아 있어도 임베딩은 항상 함께 온다 —
 * missing_fields 는 "intro_text 에서 추출을 시도했지만 명시되지 않아 못 채운 항목" 정보일 뿐이다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeamEmbeddingRefreshResponse {

    @JsonProperty("missing_fields")
    private List<String> missingFields;

    /** 임베딩 계산에 실제 사용된 원문. */
    @JsonProperty("embedding_text")
    private String embeddingText;

    /**
     * 1536 차원 임베딩. 스펙이 double 배열이라 double[] 로 받고,
     * float[] 변환은 차원 검증과 함께 저장부에서 명시적으로 한다.
     */
    @JsonProperty("embedding_vector")
    private double[] embeddingVector;

    @JsonProperty("metadata")
    private Metadata metadata;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {

        @JsonProperty("recruiting_roles")
        private List<String> recruitingRoles;

        @JsonProperty("required_skills")
        private List<String> requiredSkills;

        @JsonProperty("activity_goal")
        private String activityGoal;

        @JsonProperty("activity_style")
        private String activityStyle;

        @JsonProperty("activity_intensity")
        private String activityIntensity;

        /** intro_text 에서 못 읽어냈으면 null → Boolean (primitive 금지). */
        @JsonProperty("beginner_friendly")
        private Boolean beginnerFriendly;
    }
}
