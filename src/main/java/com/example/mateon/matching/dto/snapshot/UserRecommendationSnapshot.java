package com.example.mateon.matching.dto.snapshot;

import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.user.domain.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 역제안(팀→유저) 추천의 조회 스냅샷. {@link RecommendationSnapshot} 의 방향을 뒤집은 것이다 —
 * 질의가 팀, 후보가 유저다.
 *
 * <p>조회 TX 가 커밋된 뒤 TX 밖에서 FastAPI 를 호출하므로 여기 담긴 엔티티는 전부 detach 된
 * 상태다. User 는 지연 로딩 연관관계가 없고, MatchingIntentSlot 은 @Convert 리스트 컬럼만
 * 읽으므로 TX 밖에서도 안전하다. <b>slot.getUser()/getSession() 은 만지지 말 것</b> —
 * user 는 JOIN FETCH 로 이미 초기화돼 있지만 session 은 프록시다.
 *
 * <p>질의 쪽 값들은 팀의 team_embeddings 에서 온다 — 팀의 기존 임베딩을 그대로 질의 벡터로
 * 재사용한다(별도의 "결핍 임베딩"을 만들지 않는다).
 */
@Getter
@RequiredArgsConstructor
public class UserRecommendationSnapshot {

    /** 팀 임베딩 벡터 (team_embeddings). */
    private final float[] queryEmbedding;

    /** 룰 스코어링용 팀 메타데이터 (team_embeddings 의 AI 정규화 값). */
    private final List<String> recruitingRoles;
    private final List<String> requiredSkills;
    private final String activityStyle;
    private final Boolean beginnerFriendly;

    /** 의도 추출을 마친 후보 유저들 (멤버/지원자/기제안자 제외 완료). */
    private final List<Candidate> candidates;

    @Getter
    @RequiredArgsConstructor
    public static class Candidate {

        /** 응답 조립용 표시 정보. */
        private final User user;

        /** AI 로 보낼 정규화 메타데이터 (matching_intent_slots). */
        private final MatchingIntentSlot slot;

        /** AI 로 보낼 의도 임베딩 (user_embeddings). */
        private final float[] embedding;
    }
}
