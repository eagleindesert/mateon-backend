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

    @Enumerated(EnumType.STRING)
    @Column(name = "campus_scope", length = 20)
    private CampusScope campusScope; // enum

    // JSON 타입은 일반적인 String이나 Object로 매핑 후 직렬화/역직렬화 처리 필요
    private String target_colleges; // json (매핑 단순화를 위해 String으로 둡니다.)

    @Column(name = "external_id", unique = true, nullable = false, length = 100)
    private String externalId; // varchar(100), UNI

    @Column(name = "embedding_vector", columnDefinition = "text")
    private String embeddingVector;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt; // timestamp

    @Column(name = "updated_at")
    private LocalDateTime updatedAt; // timestamp

    // Enum 정의
    public enum Category {
        CONTEST, EXTERNAL, SCHOOL
    }
    public enum CampusScope {
        JUKJEON, CHEONAN, ALL
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