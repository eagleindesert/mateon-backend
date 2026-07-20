package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * FastAPI POST /recommendations/user-to-team 요청 본문.
 *
 * <p>AI 서버는 stateless — 우리 DB 를 모른다. 그래서 질의(사용자)와 후보(팀)의 벡터·메타데이터를
 * 백엔드가 전부 실어 보낸다.
 *
 * <p>메타데이터 타입은 {@link UserMetadata}/{@link TeamMetadata} 로 분리돼 있다. 역방향
 * ({@link TeamToUserRecommendationRequest})은 이 둘의 자리만 서로 바꾼 형태다.
 *
 * <p>벡터를 float[] 로 보내는 이유: DB(pgvector)가 float4 로 갖고 있는 값이라 double 로 넓히면
 * 0.01 이 0.009999999776 처럼 늘어져 페이로드만 커진다. Jackson 은 float 를 왕복 가능한 최단
 * 표기로 쓰므로 그대로 보내는 게 정확하고 짧다.
 */
@Getter
@AllArgsConstructor
public class UserToTeamRecommendationRequest {

    @JsonProperty("query_embedding_vector")
    private final float[] queryEmbeddingVector;

    /** 질의는 사용자다 (matching_intent_slots). */
    @JsonProperty("query_metadata")
    private final UserMetadata queryMetadata;

    private final List<Candidate> candidates;

    @Getter
    @AllArgsConstructor
    public static class Candidate {

        /** 팀 ID. 응답의 candidate_id 로 되돌아온다. */
        @JsonProperty("candidate_id")
        private final Long candidateId;

        @JsonProperty("embedding_vector")
        private final float[] embeddingVector;

        /** 후보는 팀이다 (team_embeddings 의 AI 정규화 값). */
        private final TeamMetadata metadata;
    }
}
