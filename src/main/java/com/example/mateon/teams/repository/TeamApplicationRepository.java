package com.example.mateon.teams.repository;

import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.TeamApplication;
import org.springframework.data.jpa.repository.JpaRepository;

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
}