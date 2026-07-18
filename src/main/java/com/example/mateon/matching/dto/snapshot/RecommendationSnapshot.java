package com.example.mateon.matching.dto.snapshot;

import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 조회 TX 가 커밋된 뒤 TX 밖에서 FastAPI 를 호출할 때 넘기는 값 객체.
 * (ConversationSnapshot 과 같은 역할 — 그쪽 주석 참고)
 *
 * <p>여기 담긴 Team/TeamEmbedding 은 detach 된 엔티티다. 둘 다 지연 로딩 연관관계가 없고
 * 기본 컬럼(@Convert 리스트 포함)만 있어 TX 밖에서 읽어도 안전하다.
 *
 * <p>※ 같은 dto 패키지에 있지만 request/response 와 성격이 다르다 — 서비스 사이에서만 오가고
 * 직렬화되지 않는다. 엔티티를 그대로 들고 있으므로 이걸 그대로 응답에 실으면 안 된다
 * (프론트로 나가는 모양은 dto/response 의 TeamRecommendationResponseDTO 다).
 */
@Getter
@RequiredArgsConstructor
public class RecommendationSnapshot {

    /** 사용자 의도 임베딩 (user_embeddings). */
    private final float[] queryEmbedding;

    /** 룰 스코어링용 사용자 의도 원본 (matching_intent_slots). */
    private final List<String> desiredRoles;
    private final List<String> skills;
    private final String activityStyle;
    private final String experienceLevel;

    /** 임베딩이 있는 모집 중 후보 팀들 (본인 관여 팀 제외 완료). */
    private final List<Candidate> candidates;

    @Getter
    @RequiredArgsConstructor
    public static class Candidate {

        /** 응답 조립용 표시 정보. */
        private final Team team;

        /** AI 로 보낼 벡터 + 정규화 메타데이터. */
        private final TeamEmbedding embedding;
    }
}
