package com.example.mateon.teams.repository;

import com.example.mateon.teams.domain.TeamReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamReviewRepository extends JpaRepository<TeamReview, Long> {

    /**
     * 내가 이 팀에서 이미 평가한 대상들. 재제출 화면에서 '제출 완료' 표시에 쓴다.
     *
     * <p>주의: 평가자 본인의 조회에만 써야 한다. 다른 사람에게 이 결과가 새어 나가면 익명성이 깨진다.
     */
    List<TeamReview> findByTeamIdAndReviewerId(Long teamId, Long reviewerId);

    boolean existsByTeamIdAndReviewerIdAndRevieweeId(Long teamId, Long reviewerId, Long revieweeId);
}
