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

    // ── 인원 계산 ────────────────────────────────────────────────────────────
    // capacity 는 "팀장을 포함한 팀 전체 정원"이다. 반면 지원서 집계(APPROVED)에는 팀장이
    // 절대 잡히지 않는다 — 팀장은 자기 팀에 지원할 수 없기 때문이다(TeamService.applyToTeam).
    // 그래서 두 값을 그냥 비교하면 항상 1 씩 어긋난다. 그 보정을 여기 한 곳에 모아 둔다
    // (표시용/마감 판정이 서로 다른 규칙을 쓰다 어긋나는 걸 막으려고 엔티티로 올렸다).

    /** 확정 팀원 수 = 승인된 지원자 + 팀장 1명. 화면에 보여주는 "현재 인원"이 이 값이다. */
    public static int confirmedMemberCount(int approvedApplicationCount) {
        return approvedApplicationCount + 1;
    }

    /** 정원이 찼는지. 팀장을 포함해 센다. capacity 가 없으면 마감 판단을 하지 않는다. */
    public boolean isFullWith(int approvedApplicationCount) {
        return capacity != null && confirmedMemberCount(approvedApplicationCount) >= capacity;
    }
}