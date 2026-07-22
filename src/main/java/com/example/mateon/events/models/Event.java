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
    @Column(length = 20, nullable = false)
    private Category category; // enum

    // 활동의 핵심 분야라 필수다. V21 에서 NOT NULL 로 전환했다.
    @Enumerated(EnumType.STRING)
    @Column(name = "field", length = 50, nullable = false)
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

    // 주최/주관 (예: 업스테이지). 공모전 공고에 늘 붙어 나오는 정보다.
    @Column(name = "organizer", length = 200)
    private String organizer;

    // 대상 대학교. 비어 있으면 전국 대상이다.
    // target_colleges 와 같은 LIKE 부분일치로 검색하므로 "단국대학교,고려대학교" 처럼 여러 개도 가능하다.
    @Column(name = "target_school", length = 200)
    private String targetSchool;

    /**
     * 대상 학교/캠퍼스. 전국 확장으로 자유 입력 문자열이며, CAMPUS_SCOPE_ALL 이면 제한 없음.
     *
     * @deprecated 대상 범위는 {@link #targetSchool} 로 일원화한다. 기존 행과 프론트 호환을 위해
     * 값은 계속 읽고 내려주지만, 새로 등록하는 활동에는 채우지 않는다.
     */
    @Deprecated
    @Column(name = "campus_scope", length = 50)
    private String campusScope;

    /**
     * 대상 단과대학. JSON 타입은 일반적인 String이나 Object로 매핑 후 직렬화/역직렬화 처리 필요.
     *
     * @deprecated 대상 범위는 {@link #targetSchool} 로 일원화한다. 기존 행과 프론트 호환을 위해
     * 값은 계속 읽고 내려주지만, 새로 등록하는 활동에는 채우지 않는다.
     */
    @Deprecated
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

    /**
     * campusScope 가 이 값이면 학교 구분 없이 전국 대상이다.
     *
     * @deprecated {@link #campusScope} 와 함께 폐기 예정. 전국 대상은 targetSchool 을 비워 표현한다.
     */
    @Deprecated
    public static final String CAMPUS_SCOPE_ALL = "ALL";

    // Enum 정의
    // 활동의 '종류'. 프론트 탭(공모전/대외활동/교내/기타)과 /recommended 의 묶음 단위다.
    public enum Category {
        CONTEST, EXTERNAL, SCHOOL, ETC
    }

    /**
     * 활동의 '분야'. Category(종류)와는 다른 축이다 —
     * 같은 CONTEST 라도 분야는 과학/공학일 수도, 디자인일 수도 있다.
     * 공모전 사이트의 분야 필터 칩과 같은 목록이며, label 은 화면에 그대로 노출되는 한글 표기다.
     *
     * <p>
     * 공고 하나에 분야가 여럿이면(예: "기획/아이디어, 과학/공학") 분야마다 행을 나눠 등록하고
     * 같은 externalId 를 공유시킨다. 컬럼은 분야 하나만 담을 수 있기 때문이다.
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
        PLANNING_IDEA("기획/아이디어"),
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
