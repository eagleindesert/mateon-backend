package com.example.mateon.events.dto;

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter @Setter
public class EventRequestDTO {

    @NotNull(message = "카테고리는 필수입니다. (CONTEST, EXTERNAL, SCHOOL)")
    private Category category;

    @NotBlank(message = "활동 제목은 필수입니다.")
    private String title;

    // 활동 분야(선택). category(종류)와는 다른 축이라 따로 받는다.
    // 공고의 분야가 여럿이면 분야마다 따로 등록하고 externalId 를 같게 준다(Event.Field 주석 참고).
    private Field field;

    private String description;
    private String imageUrl;
    private String detailUrl;
    private LocalDate startDate;
    private LocalDate endDate;

    // 주최/주관 (예: 업스테이지).
    private String organizer;

    // 대상 대학교. 비우면 전국 대상이다. 검색이 LIKE 부분일치라 콤마로 여러 학교를 적어도 된다.
    private String targetSchool;

    /**
     * 대상 학교/캠퍼스. 비우면 전국 대상(ALL)으로 저장한다.
     *
     * @deprecated 대상 범위는 {@link #targetSchool} 로 일원화한다. 기존 클라이언트 호환을 위해
     *             계속 받지만, 새 등록 경로에서는 쓰지 않는다.
     */
    @Deprecated
    private String campusScope;

    /**
     * 대상 단과대학. 검색이 LIKE 부분일치라 형식 제약은 없다.
     *
     * @deprecated 대상 범위는 {@link #targetSchool} 로 일원화한다. 기존 클라이언트 호환을 위해
     *             계속 받지만, 새 등록 경로에서는 쓰지 않는다.
     */
    @Deprecated
    private String targetColleges;

    private String summarizedDescription;
    private String recommendedTargets;

    // 외부 크롤러 유래 식별자(선택). 손으로 등록할 때는 비워둔다.
    private String externalId;

    // deprecated 필드(campusScope/targetColleges)를 계속 채운다. 프론트가 아직 보내고 있어
    // 여기서 끊으면 기존 클라이언트의 등록 결과가 조용히 비어버린다.
    @SuppressWarnings("deprecation")
    public Event toEntity() {
        Event event = new Event();
        event.setCategory(this.category);
        event.setField(this.field);
        event.setTitle(this.title);
        event.setDescription(this.description);
        event.setImageUrl(this.imageUrl);
        event.setDetailUrl(this.detailUrl);
        event.setStartDate(this.startDate);
        event.setEndDate(this.endDate);
        event.setOrganizer(this.organizer);
        // 빈 문자열이 쌓이면 '전국 대상'이 null 과 "" 로 갈린다. null 로 통일한다.
        event.setTargetSchool(hasText(this.targetSchool) ? this.targetSchool : null);
        // 미지정이면 학교 제한 없음. null 로 두면 캠퍼스 매칭 점수에서 조용히 탈락한다.
        event.setCampusScope(hasText(this.campusScope) ? this.campusScope : Event.CAMPUS_SCOPE_ALL);
        event.setTarget_colleges(this.targetColleges);
        event.setSummarizedDescription(this.summarizedDescription);
        event.setRecommendedTargets(this.recommendedTargets);
        // 빈 문자열이 쌓이면 '값 없음'이 null 과 "" 로 갈린다. null 로 통일한다.
        event.setExternalId(hasText(this.externalId) ? this.externalId : null);
        return event;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
