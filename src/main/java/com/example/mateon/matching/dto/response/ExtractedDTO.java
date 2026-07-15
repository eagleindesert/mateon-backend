package com.example.mateon.matching.dto.response;

import com.example.mateon.matching.client.IntentExtractResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

/**
 * 추출된 슬롯의 현재 상태. 프론트가 진행상황을 표시하는 용도.
 *
 * <p>AI 의 snake_case 를 camelCase 로 변환한다 — 우리 API 의 다른 모든 응답이 camelCase 이기
 * 때문. (반면 MatchingIntentResponseDTO.missingFields 는 AI 가 준 snake_case 를 그대로 둔다.
 * 그쪽은 값 자체가 AI 스펙과의 계약이고, 이쪽은 우리 API 의 스키마다.)
 *
 * <p>matching_intent_sessions.last_extracted_json 에 저장되는 것도 이 DTO 를 직렬화한
 * camelCase JSON 이다 (AI 원본이 아니다). 저장/응답 포맷을 하나로 맞춰 매핑을 이중으로
 * 관리하지 않는다.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractedDTO {

    private List<String> desiredRoles = Collections.emptyList();
    private List<String> skills = Collections.emptyList();
    private List<String> interests = Collections.emptyList();
    private String activityGoal;
    private String activityStyle;
    private String experienceLevel;

    /** AI 응답(snake_case) → 우리 DTO(camelCase). */
    public ExtractedDTO(IntentExtractResponse.Extracted extracted) {
        if (extracted == null) {
            return;
        }
        this.desiredRoles = nullToEmpty(extracted.getDesiredRoles());
        this.skills = nullToEmpty(extracted.getSkills());
        this.interests = nullToEmpty(extracted.getInterests());
        this.activityGoal = extracted.getActivityGoal();
        this.activityStyle = extracted.getActivityStyle();
        this.experienceLevel = extracted.getExperienceLevel();
    }

    private static List<String> nullToEmpty(List<String> value) {
        return value != null ? value : Collections.emptyList();
    }
}
