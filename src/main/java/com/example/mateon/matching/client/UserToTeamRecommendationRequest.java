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
 * <p>query_metadata 가 필요한 이유: 이게 없으면 AI 는 임베딩 유사도만 계산할 수 있고 역할 일치도
 * 같은 룰 점수를 못 낸다. 값은 의도 추출(/intents/extract) 응답의 extracted 원본과 같은 것으로,
 * matching_intent_slots 에 저장해 둔 걸 그대로 쓴다.
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

    @JsonProperty("query_metadata")
    private final QueryMetadata queryMetadata;

    private final List<Candidate> candidates;

    /** 사용자 의도 슬롯(matching_intent_slots)에서 그대로 옮겨온 값. */
    @Getter
    @AllArgsConstructor
    public static class QueryMetadata {

        @JsonProperty("desired_roles")
        private final List<String> desiredRoles;

        private final List<String> skills;

        @JsonProperty("activity_style")
        private final String activityStyle;

        @JsonProperty("experience_level")
        private final String experienceLevel;
    }

    @Getter
    @AllArgsConstructor
    public static class Candidate {

        /** 팀 ID. 응답의 candidate_id 로 되돌아온다. */
        @JsonProperty("candidate_id")
        private final Long candidateId;

        @JsonProperty("embedding_vector")
        private final float[] embeddingVector;

        private final Metadata metadata;
    }

    /**
     * 후보 팀 메타데이터.
     *
     * <p>출처가 teams 테이블이 아니라 team_embeddings 인 게 중요하다. Team.role 은 한글 자유문자열
     * ("백엔드 개발자")이고 슬롯의 desiredRoles 는 AI 정규화 코드("BE")라 서로 매칭되지 않는다.
     * team_embeddings 의 메타데이터는 AI 가 같은 어휘로 정규화해 준 값이라 양쪽이 정렬된다.
     */
    @Getter
    @AllArgsConstructor
    public static class Metadata {

        @JsonProperty("recruiting_roles")
        private final List<String> recruitingRoles;

        @JsonProperty("required_skills")
        private final List<String> requiredSkills;

        @JsonProperty("activity_style")
        private final String activityStyle;

        /** AI 가 팀 소개글에서 못 읽어냈으면 null → Boolean (primitive 금지). */
        @JsonProperty("beginner_friendly")
        private final Boolean beginnerFriendly;
    }
}
