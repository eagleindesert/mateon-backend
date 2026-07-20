package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.TeamToUserRecommendationItem;
import com.example.mateon.matching.domain.TeamToUserRecommendationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TeamToUserRecommendationLogRepository
        extends JpaRepository<TeamToUserRecommendationLog, Long> {

    /**
     * 이 팀이 이 유저를 추천받은 가장 최근 결과 1건. 제안을 보낼 때 점수/근거 문구를
     * 스냅샷으로 복사하는 데 쓴다 (프론트가 보낸 값을 믿지 않기 위해서다).
     *
     * <p>추천을 거치지 않고 보낸 제안도 허용하므로 없을 수 있다 → Optional.
     */
    @Query("""
            SELECT i FROM TeamToUserRecommendationItem i
             WHERE i.log.teamId = :teamId AND i.userId = :userId
             ORDER BY i.log.id DESC, i.rankNo ASC
            LIMIT 1
            """)
    Optional<TeamToUserRecommendationItem> findLatestItem(@Param("teamId") Long teamId,
                                                          @Param("userId") Long userId);
}
