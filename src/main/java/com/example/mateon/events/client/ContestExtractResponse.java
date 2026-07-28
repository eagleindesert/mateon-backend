package com.example.mateon.events.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * FastAPI POST /contests/extract-image 응답 본문 (포스터 이미지에서 읽어낸 공모전 정보).
 *
 * <p>@JsonNaming(SnakeCaseStrategy) 을 쓰지 않는 이유는 IntentExtractResponse 주석과 같다 —
 * Jackson 2 와 3 이 동시에 클래스패스에 있어(Boot 4 는 Jackson 3, jjwt-jackson 이 Jackson 2)
 * 어느 컨버터가 잡히느냐에 따라 조용히 무시되고 전 필드가 null 이 된다. @JsonProperty 는 양쪽에서
 * 동작한다. @JsonIgnoreProperties 도 마찬가지로 클래스에 직접 명시해야 한다 —
 * MateonBackendApplication 의 FAIL_ON_UNKNOWN_PROPERTIES 설정은 aiRestTemplate 의
 * 자체 컨버터에는 걸리지 않는다.
 *
 * <p>날짜와 enum 을 전부 String 으로 받는 이유: 이 값들은 LLM 이 이미지에서 읽어낸 것이라
 * 형식이 어긋날 수 있다. 여기서 타입 변환을 강제하면 역직렬화 단계에서 요청 전체가 502 로 죽는데,
 * 실제로는 "그 필드만 비워두고 나머지 초안은 보여주는" 편이 낫다. 변환은
 * EventExtractionService 가 관대하게 처리한다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContestExtractResponse {

    @JsonProperty("external_id")
    private String externalId;

    /** CONTEST / EXTERNAL / SCHOOL 중 하나. 매칭되는 게 없으면 ETC. */
    @JsonProperty("category")
    private String category;

    /** Event.Field 와 같은 21개 코드 중 하나. */
    @JsonProperty("field")
    private String field;

    @JsonProperty("title")
    private String title;

    @JsonProperty("organizer")
    private String organizer;

    @JsonProperty("target_school")
    private String targetSchool;

    /** yyyy-MM-dd. 이미지에서 못 읽으면 null. */
    @JsonProperty("start_date")
    private String startDate;

    @JsonProperty("end_date")
    private String endDate;

    @JsonProperty("detail_url")
    private String detailUrl;

    /**
     * AI 가 이미지 안에서 읽어낸 URL 문자열. 우리는 쓰지 않는다 —
     * 응답에 실리는 imageUrl 은 우리가 객체 저장소에 올린 원본의 URL 이다.
     */
    @JsonProperty("image_url")
    private String imageUrl;

    @JsonProperty("description")
    private String description;

    @JsonProperty("summarized_description")
    private String summarizedDescription;

    @JsonProperty("recommended_targets")
    private String recommendedTargets;
}
