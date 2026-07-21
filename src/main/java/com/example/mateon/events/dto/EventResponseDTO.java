package com.example.mateon.events.dto;

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Getter
@NoArgsConstructor // Lombok 어노테이션을 사용하여 기본 생성자 자동 생성
public class EventResponseDTO {

    private Long id;
    private Category category;
    private Field field;
    // 분야의 한글 표기. 클라이언트가 enum→한글 매핑을 따로 들고 있지 않아도 되도록 함께 내려준다
    // (UserService 가 category 를 화면용 한글로 바꾸려고 switch 를 두고 있는데, 그런 중복을 만들지 않는다).
    private String fieldLabel;
    private String title;
    private String description;
    private String imageUrl;
    private String detailUrl;
    private LocalDate startDate;
    private LocalDate endDate;
    private String campusScope;
    // target_colleges (JSON)
    private String targetColleges;
    private String summarizedDescription;
    private String recommendedTargets; // DB JSON 타입으로 저장되지만, String으로 전달

    // 엔티티를 DTO로 변환하는 생성자
    public EventResponseDTO(Event event) {
        this.id = event.getId();
        this.category = event.getCategory();
        this.field = event.getField();
        this.fieldLabel = event.getField() != null ? event.getField().getLabel() : null;
        this.title = event.getTitle();
        this.description = event.getDescription();
        this.imageUrl = event.getImageUrl();
        this.detailUrl = event.getDetailUrl();
        this.startDate = event.getStartDate();
        this.endDate = event.getEndDate();
        this.campusScope = event.getCampusScope();
        this.targetColleges = event.getTarget_colleges();
        this.summarizedDescription = event.getSummarizedDescription();
        this.recommendedTargets = event.getRecommendedTargets();
    }
}