package com.example.mateon.matching.dto.response;

import com.example.mateon.events.models.Event;
import com.example.mateon.teams.domain.Team;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 추천된 팀 1건. 점수 내림차순으로 배열에 담겨 나간다.
 *
 * <p>필드 이름은 teams 도메인의 TeamResponseDTO 와 맞춰 두었다 — 프론트가 팀 목록 카드
 * 컴포넌트를 그대로 재사용할 수 있게 하기 위해서다. 클래스를 재사용하지 않는 이유는 그쪽이
 * 팀마다 Event/인원 조회를 한 번씩 더 하는 구조라서다(N+1). 여기서는 같은 정보를 배치로 모아
 * 채운다 (RecommendationQueryService.loadDisplayInfo 참고).
 *
 * <p>eventId 만으로는 프론트가 활동을 조회할 수 없어(GET /api/events/{id} 가 없다)
 * connectedActivityTitle/Summary 를 함께 내려준다. 반대로 leaderId 는 그대로 쓸 수 있다 —
 * POST /api/chat/rooms/dm 의 targetUserId 가 곧 이 값이다.
 */
@Schema(description = """
        추천된 팀 1건. 배열은 점수 내림차순이다.

        필드 이름을 팀 목록 응답(TeamResponseDTO)과 맞춰 두었으니 카드 컴포넌트를 그대로
        재사용하면 된다. leaderId 는 POST /api/chat/rooms/dm 의 targetUserId 로 바로 쓸 수 있다.""")
@Getter
public class TeamRecommendationResponseDTO {

    private final Long teamId;
    private final String title;
    private final List<String> role;
    private final List<String> requiredSkills;
    private final String promotionText;
    private final String characteristic;
    private final Integer capacity;
    /** 확정된 팀원 수(팀장 포함) — GET /api/teams 와 같은 의미. */
    @Schema(description = "확정된 팀원 수(팀장 포함). GET /api/teams 의 같은 이름 필드와 의미가 같다.")
    private final int currentMemberCount;
    @Schema(description = "연결된 활동. 자율 프로젝트면 null.")
    private final Long eventId;
    /** 연결된 활동 제목. 자율 프로젝트(eventId=null)면 null. */
    @Schema(description = "연결된 활동 제목. 자율 프로젝트면 null. (활동 단건 조회 API 가 없어 여기에 실어 준다.)")
    private final String connectedActivityTitle;
    /** 연결된 활동의 AI 한 줄 요약. 자율 프로젝트면 null. */
    @Schema(description = "연결된 활동의 AI 한 줄 요약. 자율 프로젝트면 null.")
    private final String connectedActivitySummary;
    @Schema(description = "팀장의 userId. POST /api/chat/rooms/dm 의 targetUserId 로 그대로 쓸 수 있다.")
    private final Long leaderId;
    private final LocalDate recruitmentEndDate;

    /** AI 가 매긴 적합도 점수. 정렬 기준이자 화면 표시용. */
    @Schema(description = "AI 가 매긴 적합도 점수. 배열의 정렬 기준이자 화면 표시용이다.")
    private final double score;

    /**
     * 추천 근거 문구 (예: "BE 역할을 모집하고 있어요"). AI 가 만든 문장을 그대로 내려준다.
     * 룰 매칭이 하나도 없으면 유사도만 근거로 삼는 문구가 온다.
     */
    @Schema(description = "짧은 추천 근거 한 줄. 긴 설명이 필요하면 POST /reason/user-to-team 을 부른다.",
            example = "BE 역할을 모집하고 있어요")
    private final String label;

    /** @param event 연결된 활동. 자율 프로젝트이거나 활동이 삭제됐으면 null. */
    public TeamRecommendationResponseDTO(Team team, Event event, int currentMemberCount,
                                         double score, String label) {
        this.teamId = team.getId();
        this.title = team.getTitle();
        this.role = team.getRole();
        this.requiredSkills = team.getRequiredSkills();
        this.promotionText = team.getPromotionText();
        this.characteristic = team.getCharacteristic();
        this.capacity = team.getCapacity();
        this.currentMemberCount = currentMemberCount;
        this.eventId = team.getEventId();
        this.connectedActivityTitle = event != null ? event.getTitle() : null;
        this.connectedActivitySummary = event != null ? event.getSummarizedDescription() : null;
        this.leaderId = team.getLeaderUserId();
        this.recruitmentEndDate = team.getRecruitmentEndDate();
        this.score = score;
        this.label = label;
    }
}
