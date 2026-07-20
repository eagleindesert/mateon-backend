package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.MatchingIntentSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MatchingIntentSlotRepository extends JpaRepository<MatchingIntentSlot, Long> {

    /** 사용자당 1건 (V7 의 uk_matching_intent_slots_user). upsert 시 기존 행 조회용. */
    Optional<MatchingIntentSlot> findByUserId(Long userId);

    /**
     * 역제안(팀→유저) 추천의 후보 풀 — 의도 추출을 마친 유저 전원.
     *
     * <p>슬롯이 있다는 건 곧 의도 추출 완료다. 슬롯과 user_embeddings 는 같은 트랜잭션에서
     * 함께 저장되므로 슬롯만 보면 된다 (벡터는 호출부가 배치로 붙인다).
     *
     * <p>user 를 JOIN FETCH 하는 이유: 후보 최대 200명의 이름/학교를 응답에 실어야 하는데
     * LAZY 로 두면 그대로 N+1 이다. session 은 건드리지 않으므로 여전히 프록시다.
     */
    @Query("SELECT s FROM MatchingIntentSlot s JOIN FETCH s.user")
    List<MatchingIntentSlot> findAllWithUser();

    /**
     * 슬롯 1건 + 그 유저. 상세 이유의 요약을 조립할 때 쓴다.
     *
     * <p>{@link #findByUserId} 와 달리 user 를 함께 가져오는 이유: 요약 조립의 폴백 마지막 층이
     * users 행(전공/관심 직무/한 줄 소개)이라 슬롯만으로는 부족하다. 한 건짜리 조회라 N+1 이
     * 걱정돼서가 아니라, 프록시를 TX 밖에서 만지지 않으려는 것이다.
     */
    @Query("SELECT s FROM MatchingIntentSlot s JOIN FETCH s.user WHERE s.user.id = :userId")
    Optional<MatchingIntentSlot> findByUserIdWithUser(Long userId);
}
