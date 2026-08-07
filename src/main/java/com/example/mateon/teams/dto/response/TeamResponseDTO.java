package com.example.mateon.teams.dto.response;

import com.example.mateon.teams.domain.Team;
import com.example.mateon.events.models.Event;
import lombok.Getter;
import java.time.LocalDate;
import java.util.List;

@Getter
public class TeamResponseDTO {
    private Long id;
    private String title;
    private List<String> role;
    /**
     * 모집 중 여부. JSON 키는 {@code recruiting} 이다 — {@code isRecruiting} 이 아니다.
     * 필드명이 {@code is-} 로 시작해 Lombok 게터가 {@code isRecruiting()} 이 되고 Jackson 이
     * 접두어를 떼기 때문이다. 목록·상세 응답이 모두 이 키를 쓴다.
     *
     * <p>TODO: 나중에 {@code isRecruiting} 으로 통일한다 (docs/TODO.md 참고).
     * {@code TeamDetailResponseDTO.isLeader} 와 같은 사정이고, 프론트가 현재 이 이름을 읽고
     * 있어 <b>프론트와 동시에</b> 전환해야 한다. {@code @JsonProperty} 는 필드가 아니라
     * 게터에 달아야 두 키가 함께 나가지 않는다.
     */
    private boolean isRecruiting;
    private Long eventId;
    private String connectedActivityTitle;
    private String connectedActivitySummary;
    private String characteristic;
    private List<String> requiredSkills; // 요구 기술 스택 (optional)
    private String promotionText;
    private Integer capacity;
    private int currentMemberCount;
    private LocalDate recruitmentStartDate;
    private LocalDate recruitmentEndDate;
    private Long leaderId;

    public TeamResponseDTO(Team team, Event event,int currentMemberCount) {
        this.id = team.getId();
        this.title = team.getTitle();
        this.role = team.getRole();
        this.isRecruiting = team.getIsRecruiting();
        this.eventId = team.getEventId();
        this.connectedActivityTitle = event != null ? event.getTitle() : null;
        this.connectedActivitySummary = event != null ? event.getSummarizedDescription() : null;
        this.characteristic = team.getCharacteristic();
        this.requiredSkills = team.getRequiredSkills();
        this.promotionText = team.getPromotionText();
        this.capacity = team.getCapacity();
        this.currentMemberCount = currentMemberCount;
        this.recruitmentStartDate = team.getRecruitmentStartDate();
        this.recruitmentEndDate = team.getRecruitmentEndDate();
        this.leaderId = team.getLeaderUserId();
    }
}