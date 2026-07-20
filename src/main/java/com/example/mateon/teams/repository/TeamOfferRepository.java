package com.example.mateon.teams.repository;

import com.example.mateon.teams.domain.TeamOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TeamOfferRepository extends JpaRepository<TeamOffer, Long> {

    /** 내가 받은 제안 목록 (최신순). */
    List<TeamOffer> findByTargetUserIdOrderByCreatedAtDesc(Long targetUserId);

    /** 이 팀이 보낸 제안 목록 (최신순) — 팀장 화면. */
    List<TeamOffer> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    /** 중복 제안 방지. DB 의 uq_team_offers_pair 와 이중 방어다. */
    boolean existsByTeamIdAndTargetUserId(Long teamId, Long targetUserId);

    /**
     * 추천 후보에서 뺄 사람들 — 이 팀이 이미 제안을 보낸 유저 전원.
     *
     * <p>상태를 가리지 않는다. 거절했거나 팀장이 취소한 상대를 다시 추천해 봐야 DB 의
     * uq_team_offers_pair 에 막혀 제안을 못 보내므로, 목록에 띄우면 그냥 헛수고가 된다.
     */
    @Query("SELECT o.targetUser.id FROM TeamOffer o WHERE o.team.id = :teamId")
    List<Long> findTargetUserIdsByTeamId(@Param("teamId") Long teamId);

    /** 팀 삭제 시 제안 일괄 삭제 (DB 도 CASCADE 지만 영속성 컨텍스트를 맞춘다). */
    void deleteByTeamId(Long teamId);
}
