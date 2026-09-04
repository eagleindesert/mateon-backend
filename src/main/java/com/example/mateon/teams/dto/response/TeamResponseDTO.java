package com.example.mateon.teams.dto.response;

import com.example.mateon.teams.domain.Team;
import com.example.mateon.events.models.Event;
import com.fasterxml.jackson.annotation.JsonProperty;
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
     * 모집 중 여부. JSON 으로는 {@code recruiting} 과 {@code isRecruiting} 두 키가 함께 나간다.
     *
     * <p>
     * 필드명이 {@code is-} 로 시작해 Lombok 게터가 {@code isRecruiting()} 이 되고 Jackson 이
     * 접두어를 떼어 {@code recruiting} 으로 내보내는데, 프론트가 이 이름을 읽고 있어 없앨 수 없다.
     * {@code is} 접두로 통일하는 과도기 동안 {@link #getIsRecruiting()} 이 같은 값을
     * {@code isRecruiting} 으로도 낸다. 목록·상세 응답이 모두 이 클래스를 쓰므로 둘 다 해당된다.
     * {@code TeamDetailResponseDTO.isLeader} 와 같은 사정이다.
     *
     * <p>
     * 프론트가 {@code isRecruiting} 으로 옮기고 나면 {@code recruiting} 을 뺀다.
     */
    @Schema(description = "모집 중 여부. JSON 키는 `recruiting` 이며, 전환기 동안 `isRecruiting` 으로도 "
      + "같은 값이 나간다. 정원이 차면 false 가 된다. 활동 종료(상세 응답의 isEnded)와는 다른 축이다.")
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

    /**
     * JSON 키 {@code recruiting} 을 내는 게터. 아래 {@link #getIsRecruiting()} 이 있으면 Lombok 이
     * 이 필드의 게터를 이미 있는 것으로 보고 만들지 않아 {@code recruiting} 키가 사라지므로
     * 직접 선언한다.
     */
    public boolean isRecruiting() {
        return isRecruiting;
    }

    /**
     * {@code recruiting} 과 같은 값을 {@code isRecruiting} 키로 한 번 더 낸다 (필드 주석 참고).
     *
     * <p>
     * 키 이름을 게터에서 못박는다. {@code TeamDetailResponseDTO#getIsLeader()} 와 같은 방식이다.
     */
    @JsonProperty("isRecruiting")
    @Schema(description = "recruiting 과 같은 값. is 접두 통일 전환기 동안 두 키를 함께 낸다")
    public boolean getIsRecruiting() {
        return isRecruiting;
    }
}