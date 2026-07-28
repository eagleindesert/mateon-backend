package com.example.mateon.events.dto;

import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 포스터 이미지에서 뽑아낸 활동 등록 초안. 아직 저장된 것이 아니다.
 *
 * <p>필드 구성을 {@link EventRequestDTO} 와 맞춘 이유: 프론트가 이 응답을 사용자에게 보여주고
 * 수정만 받은 뒤 그대로 POST /api/events 의 본문으로 되돌려보낼 수 있어야 한다.
 * (deprecated 인 campusScope/targetColleges 는 새 경로에서 쓰지 않으므로 뺐다)
 */
@Getter
@Builder
public class EventExtractionResponseDTO {

    private final Category category;
    private final Field field;
    private final String title;
    private final String organizer;
    private final String targetSchool;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String detailUrl;

    /** 우리 객체 저장소에 올린 원본 이미지의 공개 URL. AI 가 읽어낸 image_url 이 아니다. */
    private final String imageUrl;

    private final String description;
    private final String summarizedDescription;
    private final String recommendedTargets;
    private final String externalId;
}
