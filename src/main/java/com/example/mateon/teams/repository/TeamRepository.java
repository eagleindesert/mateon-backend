package com.example.mateon.teams.repository;

import com.example.mateon.teams.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {

    // 특정 활동의 모집 중인 팀 조회
    List<Team> findByEventIdAndIsRecruitingTrue(Long eventId);

    // 모집 중인 팀 전체 조회 (활동 구분 없음 — 전역 추천의 후보 집합)
    List<Team> findByIsRecruitingTrue();

    // 내가 팀장인 팀 조회
    List<Team> findByLeaderUserId(Long leaderUserId);
    // 자율 프로젝트 모집 중인 팀 조회(eventId=null)
    List<Team> findAllByEventIdIsNull();
    // 카테고리별 조회
    @Query(value = "SELECT t.* FROM teams t JOIN events e ON t.event_id = e.id WHERE e.category = :category AND t.is_recruiting = true", nativeQuery = true)
    List<Team> findByEventCategory(@Param("category") String category);

    /**
     * 공모전 마감일이 지났는데 아직 종료되지 않은 팀 (자동 종료 배치용).
     *
     * <p>자율 프로젝트(event_id IS NULL)는 여기 잡히지 않는다 — 종료 기준으로 삼을 날짜가 없어서
     * 팀장 수동 종료만 가능하다.
     */
    @Query("SELECT t FROM Team t WHERE t.endedAt IS NULL AND t.eventId IN " +
           "(SELECT e.id FROM Event e WHERE e.endDate < :today)")
    List<Team> findEndedEventTeamsNotCompleted(@Param("today") LocalDate today);
}