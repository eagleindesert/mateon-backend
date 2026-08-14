package com.example.mateon.teams.dto.response;

import com.example.mateon.teams.domain.Team;
import com.example.mateon.events.models.Event;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "팀 모집글 한 건. 목록 응답의 기본 형태이며, 상세 조회는 여기에 조회자 기준 정보가 더해진다.")
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
    @Schema(description = "모집 중 여부. **JSON 키는 `recruiting`** 이다. 정원이 차면 false 가 된다. "
            + "활동 종료(상세 응답의 isEnded)와는 다른 축이다.")
    private boolean isRecruiting;
    @Schema(description = "연결된 활동. 자율 팀이면 null 이다.")
    private Long eventId;
    @Schema(description = "연결된 활동의 제목. 자율 팀이면 null.")
    private String connectedActivityTitle;
    @Schema(description = "연결된 활동의 AI 요약. 자율 팀이거나 요약 전이면 null.")
    private String connectedActivitySummary;
    private String characteristic;
    @Schema(description = "요구 기술 스택. 등록하지 않았으면 비어 있다.")
    private List<String> requiredSkills; // 요구 기술 스택 (optional)
    private String promotionText;
    @Schema(description = "팀장을 포함한 총 정원.")
    private Integer capacity;
    @Schema(description = "팀장을 포함한 현재 인원. 상세 응답의 members 배열 크기와 항상 같다.")
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