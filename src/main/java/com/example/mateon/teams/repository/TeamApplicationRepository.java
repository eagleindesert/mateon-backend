package com.example.mateon.teams.repository;

import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.TeamApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamApplicationRepository extends JpaRepository<TeamApplication, Long> {

    // 내가 쓴 지원서 목록
    List<TeamApplication> findByApplicantId(Long applicantId);

    // 특정 팀에 들어온 지원서 목록
    List<TeamApplication> findByTeamId(Long teamId);

    // 중복 지원 방지
    Optional<TeamApplication> findByTeamIdAndApplicantId(Long teamId, Long applicantId);

    // 팀 삭제 시 지원서 일괄 삭제
    void deleteByTeamId(Long teamId);

    int countByTeamIdAndStatus(Long teamId, ApplicationStatus status);
    // 특정 사용자의 승인된 지원서 목록 조회
    List<TeamApplication> findByApplicantIdAndStatus(Long applicantId, ApplicationStatus status);

    /**
     * 여러 팀의 인원 수를 한 번에 집계한다.
     *
     * <p>위 countByTeamIdAndStatus 를 팀 수만큼 도는 대신 쓴다 — 추천 목록처럼 팀이 여러 개인
     * 화면에서 팀당 쿼리를 날리면 그대로 N+1 이 된다.
     *
     * <p>지원서가 한 건도 없는 팀은 결과에 아예 나타나지 않는다 (GROUP BY 특성).
     * 호출부에서 0 으로 기본값을 채워야 한다.
     */
    @Query("SELECT a.team.id AS teamId, COUNT(a) AS memberCount FROM TeamApplication a " +
           "WHERE a.team.id IN :teamIds AND a.status = :status GROUP BY a.team.id")
    List<TeamMemberCount> countGroupedByTeamId(@Param("teamIds") List<Long> teamIds,
                                               @Param("status") ApplicationStatus status);

    /** countGroupedByTeamId 결과 projection. */
    interface TeamMemberCount {
        Long getTeamId();
        long getMemberCount();
    }
}