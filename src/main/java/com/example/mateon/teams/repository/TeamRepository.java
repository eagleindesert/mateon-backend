package com.example.mateon.teams.repository;

import com.example.mateon.teams.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {

    // 특정 활동의 모집 중인 팀 조회
    List<Team> findByEventIdAndIsRecruitingTrue(Long eventId);

    // 내가 팀장인 팀 조회
    List<Team> findByLeaderUserId(Long leaderUserId);
    // 자율 프로젝트 모집 중인 팀 조회(eventId=null)
    List<Team> findAllByEventIdIsNull();
    // 카테고리별 조회
    @Query(value = "SELECT t.* FROM teams t JOIN events e ON t.event_id = e.id WHERE e.category = :category AND t.is_recruiting = true", nativeQuery = true)
    List<Team> findByEventCategory(@Param("category") String category);
}