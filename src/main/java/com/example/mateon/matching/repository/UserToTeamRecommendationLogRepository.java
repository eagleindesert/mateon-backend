package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 아이템 전용 리포지토리는 두지 않는다 — 아이템은 항상 헤더를 통해 cascade 로 저장된다.
 */
public interface UserToTeamRecommendationLogRepository
        extends JpaRepository<UserToTeamRecommendationLog, Long> {
}
