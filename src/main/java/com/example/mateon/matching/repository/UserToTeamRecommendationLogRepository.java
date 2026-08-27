package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.UserToTeamRecommendationItem;
import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 아이템 전용 리포지토리는 두지 않는다 — 아이템은 항상 헤더를 통해 cascade 로 저장된다.
 * (아래 두 메서드는 아이템을 다루지만 헤더 리포지토리 안에 두어 그 방침을 유지한다.)
 */
public interface UserToTeamRecommendationLogRepository
  extends JpaRepository<UserToTeamRecommendationLog, Long> {

    /**
     * 이 유저가 이 팀을 추천받은 가장 최근 결과 1건.
     * {@code TeamToUserRecommendationLogRepository.findLatestItem} 의 방향만 뒤집은 쌍둥이다.
     *
     * <p>
     * 상세 이유를 생성할 때 score/rank_no/label(→ score_context)과 캐시된 reason 을 여기서
     * 읽는다. 추천받은 적 없는 팀이면 없다 → Optional.
     */
    @Query("""
            SELECT i FROM UserToTeamRecommendationItem i
             WHERE i.log.userId = :userId AND i.teamId = :teamId
             ORDER BY i.log.id DESC, i.rankNo ASC
            LIMIT 1
            """)
    Optional<UserToTeamRecommendationItem> findLatestItem(@Param("userId") Long userId,
      @Param("teamId") Long teamId);

    /**
     * 생성된 상세 이유를 캐시한다.
     *
     * <p>
     * 엔티티를 로드해 더티체킹하지 않고 UPDATE 를 직접 쏘는 이유: 이 시점엔 조회 트랜잭션이
     * 이미 커밋됐고(그 사이에 AI 를 호출했다) 아이템 id 말고는 아무것도 필요 없다. 다시 로드하면
     * 쓰지도 않을 로그 헤더까지 딸려온다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserToTeamRecommendationItem i SET i.reason = :reason WHERE i.id = :itemId")
    int updateReason(@Param("itemId") Long itemId, @Param("reason") String reason);

    /**
     * 이 후보가 실제로 선택됐다고 표시한다 (지원 발송 시점).
     * {@code updateReason} 과 같은 이유로 엔티티를 로드하지 않고 UPDATE 를 직접 쏜다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserToTeamRecommendationItem i SET i.selectedAt = :selectedAt WHERE i.id = :itemId")
    int markSelected(@Param("itemId") Long itemId, @Param("selectedAt") LocalDateTime selectedAt);

    /**
     * 선택 당시 <b>화면에 노출됐던</b> 추천 결과. 선택 피드백의 shown_candidates 를 만든다.
     *
     * <p>
     * items 에는 AI 가 점수를 매긴 결과 전체(최대 200건)가 들어 있으므로 반드시 잘라야 한다 —
     * 사용자가 본 적 없는 후보까지 보내면 "안 골랐다"로 집계돼 AI 의 선택 대비 분석이 오염된다.
     *
     * <p>
     * {@code shownCount} 가 null 인 행(V32 이전)은 자를 기준이 없어 전체가 나온다.
     * 그 판정은 호출자가 한다.
     */
    @Query("""
            SELECT i FROM UserToTeamRecommendationItem i
             WHERE i.log.id = :logId
               AND (i.log.shownCount IS NULL OR i.rankNo <= i.log.shownCount)
             ORDER BY i.rankNo ASC
            """)
    List<UserToTeamRecommendationItem> findShownItems(@Param("logId") Long logId);
}
