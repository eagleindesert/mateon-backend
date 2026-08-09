package com.example.mateon.teams.repository;

import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    /**
     * 팀의 활성 멤버 전원 (리더 포함). user 는 LAZY 로 남는다.
     *
     * <p>id 만 필요한 호출부용이다 — 프록시에서 getId() 는 초기화 없이 읽히므로 join 이 낭비다.
     * 이름·전공처럼 실제 필드를 읽어야 하면 {@link #findActiveMembersWithUser} 를 쓴다.
     */
    List<TeamMember> findByTeamIdAndLeftAtIsNull(Long teamId);

    /**
     * 팀의 활성 멤버 전원 (리더 포함) + user. 평가 대상 목록이자 인원 표시의 단일 출처.
     *
     * <p>user 를 함께 읽는다 — 곧바로 이름/전공을 꺼내는 호출부에서 LAZY 로 두면 팀원 수만큼
     * 추가 쿼리가 나간다.
     */
    @Query("SELECT m FROM TeamMember m JOIN FETCH m.user WHERE m.team.id = :teamId AND m.leftAt IS NULL")
    List<TeamMember> findActiveMembersWithUser(@Param("teamId") Long teamId);

    /**
     * 팀의 활성 멤버 <b>유저만</b> (리더 포함).
     *
     * <p>{@link #findActiveMembersWithUser} 와 달리 TeamMember 를 영속성 컨텍스트에 올리지 않는다.
     * 팀 삭제처럼 곧 team 행이 사라지는 흐름에서 필요하다 — TeamMember 가 컨텍스트에 남아 있으면
     * 삭제된 Team 을 참조한 채로 flush 되어 TransientPropertyValueException 이 난다.
     */
    @Query("SELECT m.user FROM TeamMember m WHERE m.team.id = :teamId AND m.leftAt IS NULL")
    List<User> findActiveMemberUsers(@Param("teamId") Long teamId);

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
