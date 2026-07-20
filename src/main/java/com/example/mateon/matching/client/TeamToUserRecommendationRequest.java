package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * FastAPI POST /recommendations/team-to-user 요청 본문 — 역제안(팀이 유저를 찾는) 방향.
 *
 * <p>{@link UserToTeamRecommendationRequest} 와 스키마가 완전히 같고 질의/후보의 자리만
 * 뒤집힌다. 질의가 팀(TeamMetadata), 후보가 유저(UserMetadata)다.
 *
 * <p>query_embedding_vector 는 <b>팀의 기존 embedding_vector(team_embeddings)를 그대로
 * 재사용</b>한다 — "결핍 임베딩"을 따로 계산하지 않는다. 팀 embedding_text 가 이미 모집 역할/
 * 요구 스킬 중심으로 렌더링돼 있어 "이 팀이 뭘 필요로 하는가"를 충분히 대변하기 때문이다.
 */
@Getter
@AllArgsConstructor
public class TeamToUserRecommendationRequest {

    @JsonProperty("query_embedding_vector")
    private final float[] queryEmbeddingVector;

    /** 질의는 팀이다 (team_embeddings). */
    @JsonProperty("query_metadata")
    private final TeamMetadata queryMetadata;

    private final List<Candidate> candidates;

    @Getter
    @AllArgsConstructor
    public static class Candidate {

        /** 유저 ID. 응답의 candidate_id 로 되돌아온다. */
        @JsonProperty("candidate_id")
        private final Long candidateId;

        /** 유저의 의도 임베딩 (user_embeddings). */
        @JsonProperty("embedding_vector")
        private final float[] embeddingVector;

        /** 후보는 유저다 (matching_intent_slots). */
        private final UserMetadata metadata;
    }
}
