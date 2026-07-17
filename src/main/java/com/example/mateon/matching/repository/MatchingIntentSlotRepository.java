package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.MatchingIntentSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MatchingIntentSlotRepository extends JpaRepository<MatchingIntentSlot, Long> {

    /** 사용자당 1건 (V7 의 uk_matching_intent_slots_user). upsert 시 기존 행 조회용. */
    Optional<MatchingIntentSlot> findByUserId(Long userId);
}
