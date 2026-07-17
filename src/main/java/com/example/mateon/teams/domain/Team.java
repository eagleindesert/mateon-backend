// Team.java (Teams 테이블 매핑)
package com.example.mateon.teams.domain;

import com.example.mateon.teams.converter.RoleListConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "teams")
@Getter @Setter
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private Long eventId;
    private String title;
    private String promotionText;  //진행 방식 및 한 줄 소개
    private String characteristic; //특성
    private Integer capacity;
    @Convert(converter = RoleListConverter.class) //
    private List<String> role;

    // 요구 기술 스택 (optional — 프론트가 안 보내도 정상 동작). role 과 같은 CSV 저장.
    @Convert(converter = RoleListConverter.class)
    @Column(name = "required_skills", columnDefinition = "text")
    private List<String> requiredSkills;
    @Column(name = "recruitment_start_date")
    private LocalDate recruitmentStartDate;

    @Column(name = "recruitment_end_date")
    private LocalDate recruitmentEndDate;

    @Column(name = "leader_user_id")
    private Long leaderUserId;

    @Column(name = "is_recruiting")
    private Boolean isRecruiting = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}