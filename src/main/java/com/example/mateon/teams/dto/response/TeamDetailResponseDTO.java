
package com.example.mateon.teams.dto.response;

import com.example.mateon.events.models.Event;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.user.domain.User; // User 임포트 필요
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class TeamDetailResponseDTO extends TeamResponseDTO { // [핵심] 상속 받음!

    // 추가된 필드들
    private boolean isLeader;
    private boolean hasApplied;
    private String leaderName;   // 이름 (예: 김루미)
    private String leaderMajor;  // 전공 (예: SW융합대학 통계데이터사이언스)
    private String leaderGrade;  // 학년 (예: 3학년)
    private String leaderCollege; // 딘과대
    // 팀장의 협업 온도. 평가가 2건 미만이면 null (비공개).
    private BigDecimal leaderCollaborationTemperature;

    public TeamDetailResponseDTO(Team team, Event event, int currentMemberCount, boolean isLeader, boolean hasApplied,
                                 User leader, BigDecimal leaderCollaborationTemperature) {
        // 부모(TeamResponseDTO)의 생성자를 먼저 호출해서 기본 필드 채우기
        super(team, event, currentMemberCount);
        // 내 필드 채우기
        this.isLeader = isLeader;
        this.hasApplied = hasApplied;
        this.leaderCollaborationTemperature = leaderCollaborationTemperature;
        if (leader != null) {
            this.leaderName = leader.getName(); // 혹은 getNickname()
            // User 엔티티에 해당 필드들이 있다고 가정합니다.
            // 만약 없으면 User 엔티티에 먼저 추가해야 합니다!
            this.leaderMajor = leader.getMajor();
            this.leaderGrade = leader.getGrade();
            this.leaderCollege = leader.getCollege();
        }
    }
}