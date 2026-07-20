package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.UserToTeamRecommendationItem;
import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * <p>상세 이유를 생성할 때 score/rank_no/label(→ score_context)과 캐시된 reason 을 여기서
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
     * <p>엔티티를 로드해 더티체킹하지 않고 UPDATE 를 직접 쏘는 이유: 이 시점엔 조회 트랜잭션이
     * 이미 커밋됐고(그 사이에 AI 를 호출했다) 아이템 id 말고는 아무것도 필요 없다. 다시 로드하면
     * 쓰지도 않을 로그 헤더까지 딸려온다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserToTeamRecommendationItem i SET i.reason = :reason WHERE i.id = :itemId")
    int updateReason(@Param("itemId") Long itemId, @Param("reason") String reason);
}
