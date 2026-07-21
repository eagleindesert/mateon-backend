package com.example.mateon.events.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "events") // 테이블 이름 지정
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // bigint, auto_increment

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Category category; // enum

    // 활동 분야(선택). 기존 데이터에는 값이 없으므로 null 을 허용한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "field", length = 50)
    private Field field;

    private String title; // varchar(255)

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Column(name = "detail_url", columnDefinition = "text")
    private String detailUrl;

    @Column(name = "start_date")
    private LocalDate startDate; // date 타입

    @Column(name = "end_date")
    private LocalDate endDate; // date 타입

    // 대상 학교/캠퍼스. 전국 확장으로 자유 입력 문자열이며, CAMPUS_SCOPE_ALL 이면 제한 없음.
    @Column(name = "campus_scope", length = 50)
    private String campusScope;

    // JSON 타입은 일반적인 String이나 Object로 매핑 후 직렬화/역직렬화 처리 필요
    private String target_colleges; // json (매핑 단순화를 위해 String으로 둡니다.)

    // 외부 크롤러가 수집한 원본 식별자. API 로 직접 등록한 활동에는 없으므로 선택 필드다(V18).
    @Column(name = "external_id", length = 100)
    private String externalId; // varchar(100)

    @Column(name = "embedding_vector", columnDefinition = "text")
    private String embeddingVector;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt; // timestamp

    @Column(name = "updated_at")
    private LocalDateTime updatedAt; // timestamp

    // campusScope 가 이 값이면 학교 구분 없이 전국 대상이다.
    public static final String CAMPUS_SCOPE_ALL = "ALL";

    // Enum 정의
    // 활동의 '종류'. 프론트 탭(공모전/대외활동/교내)과 /recommended 의 묶음 단위다.
    public enum Category {
        CONTEST, EXTERNAL, SCHOOL
    }

    /**
     * 활동의 '분야'. Category(종류)와는 다른 축이다 —
     * 같은 CONTEST 라도 분야는 과학/공학일 수도, 디자인일 수도 있다.
     * 공모전 사이트의 분야 필터 칩과 같은 목록이며, label 은 화면에 그대로 노출되는 한글 표기다.
     *
     * <p>
     * [주의] 여기에 값을 추가/삭제하면 DB 의 events_field_check 제약도 마이그레이션으로 함께
     * 갱신해야 한다. 안 그러면 새 값으로 저장할 때 제약 위반으로 실패한다.
     */
    public enum Field {
        TRAVEL_HOTEL_AIRLINE("여행/호텔/항공"),
        PRESS_MEDIA("언론/미디어"),
        CULTURE_HISTORY("문화/역사"),
        EVENT_FESTIVAL("행사/페스티벌"),
        EDUCATION("교육"),
        DESIGN_PHOTO_ART_VIDEO("디자인/사진/예술/영상"),
        ECONOMY_FINANCE("경제/금융"),
        MANAGEMENT_CONSULTING_MARKETING("경영/컨설팅/마케팅"),
        POLITICS_SOCIETY_LAW("정치/사회/법률"),
        SPORTS_FITNESS("체육/헬스"),
        MEDICAL_HEALTH("의료/보건"),
        BEAUTY_COSMETICS("뷰티/미용/화장품"),
        SCIENCE_ENGINEERING_TECH_IT("과학/공학/기술/IT"),
        COOKING_FOOD("요리/식품"),
        STARTUP_SELF_DEVELOPMENT("창업/자기계발"),
        ENVIRONMENT_ENERGY("환경/에너지"),
        CONTENTS("콘텐츠"),
        SOCIAL_CONTRIBUTION_EXCHANGE("사회공헌/교류"),
        DISTRIBUTION_LOGISTICS("유통/물류"),
        ETC("기타");

        private final String label;

        Field(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
    @Column(name = "summarized_description", columnDefinition = "VARCHAR(500)")
    private String summarizedDescription;
    @Column(name = "recommended_targets", columnDefinition = "text")
    private String recommendedTargets;

    // 엔티티가 저장되기 전에 실행
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 엔티티가 업데이트되기 전에 실행
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}