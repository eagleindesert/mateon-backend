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