package com.example.mateon.events.dto;

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Schema(description = "활동(공모전 등) 한 건. 검색·목록·등록 응답이 모두 이 형태다.")
@Getter
@NoArgsConstructor // Lombok 어노테이션을 사용하여 기본 생성자 자동 생성
public class EventResponseDTO {

    private Long id;
    private Category category;
    private Field field;
    // 분야의 한글 표기. 클라이언트가 enum→한글 매핑을 따로 들고 있지 않아도 되도록 함께 내려준다
    // (UserService 가 category 를 화면용 한글로 바꾸려고 switch 를 두고 있는데, 그런 중복을 만들지 않는다).
    @Schema(description = "field 의 한글 표기. enum→한글 매핑을 클라이언트가 따로 들고 있지 않아도 되도록 함께 내려준다.",
      example = "과학/공학/기술/IT")
    private String fieldLabel;
    private String title;
    private String description;
    private String imageUrl;
    private String detailUrl;
    private LocalDate startDate;
    private LocalDate endDate;
    // 주최/주관 (예: 업스테이지)
    private String organizer;
    // 대상 대학교. 비어 있으면 전국 대상이다.
    @Schema(description = "대상 대학교. **비어 있으면 전국 대상**이다.")
    private String targetSchool;

    /**
     * 대상 학교/캠퍼스.
     *
     * @deprecated 대상 범위는 {@link #targetSchool} 로 일원화한다. 이미 이 필드를 읽고 있는
     * 프론트가 있어 응답에서 빼지 않는다 — 빼면 기존 화면이 깨진다.
     */
    @Schema(deprecated = true, description = "[폐기 예정] 대상 범위는 targetSchool 로 일원화한다. 새 화면에서는 읽지 말 것.")
    @Deprecated
    private String campusScope;

    /**
     * 대상 단과대학 (target_colleges, JSON 문자열).
     *
     * @deprecated 대상 범위는 {@link #targetSchool} 로 일원화한다. 이미 이 필드를 읽고 있는
     * 프론트가 있어 응답에서 빼지 않는다 — 빼면 기존 화면이 깨진다.
     */
    @Schema(deprecated = true, description = "[폐기 예정] 대상 범위는 targetSchool 로 일원화한다. 새 화면에서는 읽지 말 것.")
    @Deprecated
    private String targetColleges;
    @Schema(description = "AI 한 줄 요약. 요약 전이면 null.")
    private String summarizedDescription;
    @Schema(description = "추천 대상 서술. DB 에는 JSON 으로 저장되지만 응답에는 문자열로 나간다.")
    private String recommendedTargets; // DB JSON 타입으로 저장되지만, String으로 전달

    /**
     * 조회한 사용자가 이 활동을 북마크했는지. 비로그인 조회는 항상 false 다.
     *
     * <p>
     * 필드명을 {@code isBookmarked} 가 아니라 {@code bookmarked} 로 둔다 — 그래야 Lombok 게터가
     * {@code isBookmarked()} 가 되어 JSON 키가 {@code bookmarked} 로 안정적으로 나온다.
     */
    @Schema(description = "조회한 사용자가 이 활동을 북마크했는지. **비로그인 조회는 항상 false** 다.")
    private boolean bookmarked;

    /**
     * 북마크 여부를 모르는(또는 따질 필요 없는) 자리에서 쓰는 생성자. 등록 응답처럼 방금 만든
     * 활동을 돌려줄 때가 그렇다. 시그니처를 유지해 기존 호출부를 건드리지 않는다.
     */
    public EventResponseDTO(Event event) {
        this(event, false);
    }

    // 엔티티를 DTO로 변환하는 생성자.
    // deprecated 필드도 계속 채운다 — 응답 계약을 지켜야 기존 프론트가 안 깨진다.
    @SuppressWarnings("deprecation")
    public EventResponseDTO(Event event, boolean bookmarked) {
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
        this.organizer = event.getOrganizer();
        this.targetSchool = event.getTargetSchool();
        this.campusScope = event.getCampusScope();
        this.targetColleges = event.getTarget_colleges();
        this.summarizedDescription = event.getSummarizedDescription();
        this.recommendedTargets = event.getRecommendedTargets();
        this.bookmarked = bookmarked;
    }
}
