package com.example.mateon.portfolios.repository;

import com.example.mateon.portfolios.domain.UserPortfolio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPortfolioRepository extends JpaRepository<UserPortfolio, Long> {

    /**
     * 캐시 조회. AI 를 부르기 전에 이 메서드가 히트하면 Vision 호출 한 번을 통째로 아낀다.
     *
     * <p>
     * user 연관을 태우지 않고 user.id 로 찾는다 — 조회 시점에 우리가 가진 건 JWT 의 userId 뿐이고,
     * User 엔티티를 먼저 로드하면 캐시 히트에도 불필요한 SELECT 가 한 번 더 나간다.
     */
    Optional<UserPortfolio> findByUserIdAndPdfId(Long userId, String pdfId);
}
