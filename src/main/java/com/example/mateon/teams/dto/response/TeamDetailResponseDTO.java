
package com.example.mateon.teams.dto.response;

import com.example.mateon.events.models.Event;
import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.user.domain.User; // User 임포트 필요
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "팀 모집글 상세. 목록 응답에 조회자 기준 정보와 확정 팀원 명단이 더해진다.")
@Getter
public class TeamDetailResponseDTO extends TeamResponseDTO { // [핵심] 상속 받음!

    // 추가된 필드들
    /**
     * 조회자가 이 팀의 팀장인지.
     *
     * <p>JSON 키는 {@code leader} 다 — {@code isLeader} 가 아니다. 필드명이 {@code is-} 로 시작해
     * Lombok 게터가 {@code isLeader()} 가 되고 Jackson 이 접두어를 떼기 때문이다. 프론트가 이미
     * 이 이름으로 읽고 있으므로 {@code @JsonProperty} 로 바꾸면 프론트가 깨진다.
     *
     * <p>같은 응답의 {@code members[].isLeader} 와 이름이 다른데, 그쪽은 나중에 추가되며
     * {@code @JsonProperty} 가 붙어 처음부터 {@code isLeader} 로 나갔다. 헷갈리기 쉬우니
     * 직렬화 테스트가 두 이름을 각각 못박아 둔다.
     *
     * <p>TODO: 나중에 {@code isLeader} 로 통일한다 (docs/TODO.md 참고). 한 응답 안에 두 규칙이
     * 섞여 있는 지금 상태가 헷갈리기 때문이다. 서버만 바꾸면 프론트가 조용히 깨지므로
     * <b>프론트와 동시에</b> 전환해야 한다. 바꿀 때는 {@code @JsonProperty("isLeader")} 를
     * 필드가 아니라 게터에 달고({@link #isEnded()} 참고), 직렬화 테스트와
     * {@code 05_team.ps1} 5.2f 의 assert 방향도 함께 뒤집는다.
     */
    @Schema(description = "조회자가 이 팀의 팀장인지. 비로그인이면 false. JSON 키는 leader")
    private boolean isLeader;
    @Schema(description = "조회자가 이 팀에 지원한 적이 있는지. myApplicationStatus != null 과 같다")
    private boolean hasApplied;
    private String leaderName;   // 이름 (예: 김루미)
    private String leaderMajor;  // 전공 (예: SW융합대학 통계데이터사이언스)
    private String leaderGrade;  // 학년 (예: 3학년)
    private String leaderCollege; // 딘과대
    // 팀장의 협업 온도. 평가가 2건 미만이면 null (비공개).
    @Schema(description = "팀장의 협업 온도. 평가가 부족하면 비공개(null)")
    private BigDecimal leaderCollaborationTemperature;

    /** 조회자의 지원 상태. 지원한 적이 없으면 null — hasApplied 의 상세판이다. */
    @Schema(description = "조회자의 지원 상태. 지원한 적이 없으면 null")
    private ApplicationStatus myApplicationStatus;

    /**
     * 활동 종료 여부. isRecruiting(모집 마감)과 다른 축이다 — 정원이 차도 활동은 그때부터 시작한다.
     *
     * <p>키 이름을 게터에서 못박는다. 필드명이 {@code is-} 로 시작하면 Lombok 게터가
     * {@code isEnded()} 가 되고 Jackson 이 접두어를 떼어 {@code ended} 로 내보내기 때문이다.
     * 주의: {@code @JsonProperty} 를 <b>필드</b>에 달면 게터가 만든 {@code ended} 와 병합되지 않아
     * 두 키가 모두 나간다. 반드시 게터 쪽에 달아야 한다. (아래 getter 참고)
     */
    @Schema(description = "활동 종료 여부. 모집 마감(isRecruiting=false)과는 다른 축이다")
    private boolean isEnded;

    /**
     * 확정된 팀원 명단 (리더 포함). currentMemberCount 와 같은 출처에서 나온다.
     *
     * <p>이 필드가 없던 동안 프론트가 볼 수 있는 유일한 명단은 팀장 전용 지원서 목록(APPROVED)이었다.
     * 역제안으로 합류한 사람은 team_applications 에 행이 없어 그 명단에 안 잡히고, 그래서 인원 수만
     * 혼자 커 보이는 현상이 있었다.
     */
    @Schema(description = "확정된 팀원 명단 (팀장 포함). 항상 currentMemberCount 와 같은 크기다")
    private List<MemberSummary> members;

    /** 명단 한 줄. 팀장/팀원을 role 하나로 구분한다. */
    @Schema(name = "TeamMemberSummary", description = "팀원 명단 한 줄")
    public record MemberSummary(
            Long userId,
            String name,
            String major,
            // isEnded 와 같은 사정 — 키를 못박는다.
            @JsonProperty("isLeader")
            @Schema(description = "이 팀원이 팀장인지") boolean isLeader) {
    }

    public TeamDetailResponseDTO(Team team, Event event, List<TeamMember> members, boolean isLeader,
                                 ApplicationStatus myApplicationStatus,
                                 User leader, BigDecimal leaderCollaborationTemperature) {
        // 부모(TeamResponseDTO)의 생성자를 먼저 호출해서 기본 필드 채우기
        // 인원 수는 명단의 크기다. 따로 세면 둘이 어긋날 수 있다.
        super(team, event, members.size());
        // 내 필드 채우기
        this.isLeader = isLeader;
        this.myApplicationStatus = myApplicationStatus;
        this.hasApplied = myApplicationStatus != null;
        this.isEnded = team.isEnded();
        this.leaderCollaborationTemperature = leaderCollaborationTemperature;
        this.members = members.stream()
                .map(member -> new MemberSummary(
                        member.getUser().getId(),
                        member.getUser().getName(),
                        member.getUser().getMajor(),
                        member.getRole() == TeamMemberRole.LEADER))
                .toList();
        if (leader != null) {
            this.leaderName = leader.getName(); // 혹은 getNickname()
            // User 엔티티에 해당 필드들이 있다고 가정합니다.
            // 만약 없으면 User 엔티티에 먼저 추가해야 합니다!
            this.leaderMajor = leader.getMajor();
            this.leaderGrade = leader.getGrade();
            this.leaderCollege = leader.getCollege();
        }
    }

    /**
     * 직접 선언한 게터다 — Lombok 은 같은 이름의 메서드가 이미 있으면 생성하지 않는다.
     * 여기에 {@code @JsonProperty} 를 달아야 JSON 키가 {@code isEnded} 하나로 확정된다.
     */
    @JsonProperty("isEnded")
    public boolean isEnded() {
        return isEnded;
    }
}
