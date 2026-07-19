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

    // 프로젝트 종료 시각. NULL 이면 진행 중.
    // isRecruiting 은 '모집 마감'이지 '프로젝트 종료'가 아니다 — 정원이 차도 활동은 그때부터 시작한다.
    // 협업 온도 평가는 이 시점 이후에만 열린다.
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    // ── 인원 계산 ────────────────────────────────────────────────────────────
    // capacity 는 "팀장을 포함한 팀 전체 정원"이고, team_members 에는 팀장도 LEADER 로 들어 있다.
    // 즉 두 값을 그대로 비교하면 된다. (예전엔 지원서 집계에 팀장이 안 잡혀 +1 보정 함수가
    // 필요했는데, V12 에서 멤버십을 실체 테이블로 올리며 그 보정이 사라졌다.)

    /** 정원이 찼는지. capacity 가 없으면 마감 판단을 하지 않는다. */
    public boolean isFullWith(int activeMemberCount) {
        return capacity != null && activeMemberCount >= capacity;
    }

    // ── 종료/평가 ────────────────────────────────────────────────────────────

    public boolean isEnded() {
        return endedAt != null;
    }

    /**
     * 지금 평가를 받을 수 있는 팀인지. 종료됐고 평가 기간이 아직 남아 있어야 한다.
     *
     * <p>기간을 닫는 이유는 두 가지다 — 기억이 희미해진 뒤의 평가는 신호 대비 잡음이 크고,
     * 창구가 계속 열려 있으면 나중에 감정이 상했을 때 보복 평가를 하러 돌아올 수 있다.
     */
    public boolean isReviewableAt(LocalDateTime now, int reviewWindowDays) {
        return isEnded() && now.isBefore(reviewDeadline(reviewWindowDays));
    }

    public LocalDateTime reviewDeadline(int reviewWindowDays) {
        return endedAt == null ? null : endedAt.plusDays(reviewWindowDays);
    }
}