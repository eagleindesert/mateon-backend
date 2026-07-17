package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * FastAPI POST /intents/extract 응답 본문.
 *
 * <p>@JsonNaming(SnakeCaseStrategy) 을 쓰지 않는 이유: 이 프로젝트는 Jackson 2 와 3 이 동시에
 * 클래스패스에 있다 (Boot 4 는 Jackson 3, jjwt-jackson 이 Jackson 2 를 끌고 옴). @JsonNaming 은
 * J2/J3 가 서로 다른 클래스라, 어느 컨버터가 잡히느냐에 따라 조용히 무시되어 전 필드가 null 이
 * 될 수 있다. 반면 @JsonProperty 는 jackson-annotations 에 있고 jackson-databind 3.x 가 이
 * 아티팩트에 의존하므로 양쪽 모두에서 동작한다.
 *
 * <p>@JsonIgnoreProperties 를 클래스에 명시하는 이유: MateonBackendApplication 의
 * FAIL_ON_UNKNOWN_PROPERTIES=false 설정은 그 ObjectMapper 빈에만 걸리고, aiRestTemplate 이
 * 자체 구성하는 컨버터에는 적용되지 않는다. AI 가 필드를 추가해도 깨지지 않게 한다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntentExtractResponse {

    /** 비어있으면 추출 완료 → 임베딩이 함께 온다. */
    @JsonProperty("missing_fields")
    private List<String> missingFields;

    @JsonProperty("extracted")
    private Extracted extracted;

    /** 임베딩의 원문. missing_fields 가 남아있으면 null. */
    @JsonProperty("embedding_text")
    private String embeddingText;

    /**
     * 1536 차원 임베딩. missing_fields 가 남아있으면 null.
     * 스펙이 double 배열이라 double[] 로 받고, float[] 변환은 차원 검증과 함께 명시적으로 한다.
     */
    @JsonProperty("embedding_vector")
    private double[] embeddingVector;

    /** 프론트가 그대로 화면에 보여주면 되는 챗봇 문구. */
    @JsonProperty("assistant_message")
    private String assistantMessage;

    /** missing_fields 가 비어있으면 추출 완료. */
    public boolean isCompleted() {
        return missingFields == null || missingFields.isEmpty();
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Extracted {

        @JsonProperty("desired_roles")
        private List<String> desiredRoles;

        @JsonProperty("skills")
        private List<String> skills;

        @JsonProperty("interests")
        private List<String> interests;

        @JsonProperty("activity_goal")
        private String activityGoal;

        @JsonProperty("activity_style")
        private String activityStyle;

        @JsonProperty("experience_level")
        private String experienceLevel;
    }
}
