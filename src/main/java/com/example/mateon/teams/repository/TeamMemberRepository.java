package com.example.mateon.teams.repository;

import com.example.mateon.teams.domain.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    /** 팀의 활성 멤버 전원 (리더 포함). 평가 대상 목록이자 인원 표시의 단일 출처. */
    List<TeamMember> findByTeamIdAndLeftAtIsNull(Long teamId);

    /** 평가 자격 검증용. */
    boolean existsByTeamIdAndUserIdAndLeftAtIsNull(Long teamId, Long userId);

    int countByTeamIdAndLeftAtIsNull(Long teamId);

    /** 내가 속한 팀 (리더로 만든 팀 + 승인되어 들어간 팀이 한 번에 나온다). */
    List<TeamMember> findByUserIdAndLeftAtIsNull(Long userId);

    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

    /**
     * 여러 팀의 인원 수를 한 번에 집계한다. 팀당 count 를 도는 N+1 을 피하려는 것으로,
     * TeamApplicationRepository.countGroupedByTeamId 가 하던 역할을 그대로 이어받는다.
     *
     * <p>차이가 하나 있다: 리더도 team_members 에 있으므로 결과가 곧 실제 인원이다.
     * 예전처럼 +1 보정을 하면 안 된다.
     *
     * <p>멤버가 한 명도 없는 팀은 결과에 나타나지 않는다(GROUP BY 특성). 호출부에서 0 으로 채운다.
     */
    @Query("SELECT m.team.id AS teamId, COUNT(m) AS memberCount FROM TeamMember m " +
           "WHERE m.team.id IN :teamIds AND m.leftAt IS NULL GROUP BY m.team.id")
    List<TeamMemberCount> countGroupedByTeamId(@Param("teamIds") List<Long> teamIds);

    /** countGroupedByTeamId 결과 projection. */
    interface TeamMemberCount {
        Long getTeamId();
        long getMemberCount();
    }
}
