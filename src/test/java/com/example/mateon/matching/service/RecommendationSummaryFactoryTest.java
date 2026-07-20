package com.example.mateon.matching.service;

import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요약 조립기는 순수 함수라 DB 없이 검증한다.
 *
 * <p>여기서 못 박고 싶은 건 "문구가 예쁜가"가 아니라 <b>어느 층까지 내려가는가</b>다 —
 * 상위 층이 비었을 때 조용히 빈 문자열을 보내면 AI 는 근거 없이 그럴듯한 이유를 지어내고,
 * 아무도 눈치채지 못한 채 나간다.
 */
class RecommendationSummaryFactoryTest {

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    /** 슬롯은 setter 가 없고 update() 로만 채워진다 (사용자당 1건 upsert 규약). */
    private static MatchingIntentSlot slot(List<String> desiredRoles, List<String> skills,
                                           List<String> interests, String activityGoal,
                                           String activityStyle, String experienceLevel,
                                           String embeddingText) {
        MatchingIntentSlot slot = new MatchingIntentSlot(new User());
        slot.update(null, desiredRoles, skills, interests,
                activityGoal, activityStyle, experienceLevel, embeddingText);
        return slot;
    }

    private static MatchingIntentSlot emptySlot() {
        return slot(null, null, null, null, null, null, null);
    }

    private static User emptyUser() {
        return new User();
    }

    private static TeamEmbedding embedding(String embeddingText, List<String> recruitingRoles,
                                           List<String> requiredSkills, String activityStyle,
                                           Boolean beginnerFriendly) {
        TeamEmbedding embedding = new TeamEmbedding();
        embedding.setEmbeddingText(embeddingText);
        embedding.setRecruitingRoles(recruitingRoles);
        embedding.setRequiredSkills(requiredSkills);
        embedding.setActivityStyle(activityStyle);
        embedding.setBeginnerFriendly(beginnerFriendly);
        return embedding;
    }

    private static TeamEmbedding emptyEmbedding() {
        return embedding(null, null, null, null, null);
    }

    private static Team emptyTeam() {
        return new Team();
    }

    // ── 유저 요약 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("유저 요약의 폴백 층")
    class UserSummaryLayers {

        @Test
        @DisplayName("1층: embedding_text 가 있으면 그대로 쓴다 (가공하지 않는다)")
        void usesEmbeddingText() {
            MatchingIntentSlot slot = slot(List.of("BE"), List.of("React"), null, null, null,
                    "beginner", "백엔드 / React / beginner");

            assertThat(RecommendationSummaryFactory.userSummary(slot, emptyUser()))
                    .isEqualTo("백엔드 / React / beginner");
        }

        @Test
        @DisplayName("2층: embedding_text 가 없으면 슬롯의 정규화 값으로 조립한다")
        void fallsBackToSlotFields() {
            MatchingIntentSlot slot = slot(List.of("BE"), List.of("React", "TypeScript"),
                    List.of("커머스"), "포트폴리오용 프로젝트", "주 2회 오프라인", "beginner", null);

            assertThat(RecommendationSummaryFactory.userSummary(slot, emptyUser()))
                    .isEqualTo("BE / React, TypeScript / 커머스 / 포트폴리오용 프로젝트 / "
                            + "주 2회 오프라인 / beginner");
        }

        @Test
        @DisplayName("2층: 비어 있는 항목은 건너뛰고 구분자도 남기지 않는다")
        void skipsBlankParts() {
            MatchingIntentSlot slot = slot(List.of("BE"), List.of(), null, "  ", null,
                    "beginner", null);

            assertThat(RecommendationSummaryFactory.userSummary(slot, emptyUser()))
                    .isEqualTo("BE / beginner");
        }

        @Test
        @DisplayName("3층: 슬롯이 통째로 비면 users 행(전공/관심직무/한 줄 소개)으로 내려간다")
        void fallsBackToUserProfile() {
            User user = new User();
            user.setMajor("컴퓨터공학과");
            user.setGrade("3학년");
            user.setInterestJobPrimary("백엔드");
            user.setInterestJobTertiary("데이터");
            user.setTagline("꾸준히 하는 게 제일 어렵더라고요");

            assertThat(RecommendationSummaryFactory.userSummary(emptySlot(), user))
                    .isEqualTo("컴퓨터공학과 / 3학년 / 백엔드, 데이터 / 꾸준히 하는 게 제일 어렵더라고요");
        }

        @Test
        @DisplayName("4층: 조립할 게 하나도 없으면 빈 문자열 (null 이 아니다)")
        void emptyStringWhenNothingAvailable() {
            assertThat(RecommendationSummaryFactory.userSummary(emptySlot(), emptyUser()))
                    .isEmpty();
        }
    }

    // ── 팀 요약 ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("팀 요약의 폴백 층")
    class TeamSummaryLayers {

        @Test
        @DisplayName("1층: embedding_text 를 그대로 쓴다")
        void usesEmbeddingText() {
            TeamEmbedding embedding = embedding("팀 소개: 커머스 플랫폼\n모집 역할: BE",
                    List.of("BE"), null, null, null);

            assertThat(RecommendationSummaryFactory.teamSummary(embedding, emptyTeam()))
                    .isEqualTo("팀 소개: 커머스 플랫폼\n모집 역할: BE");
        }

        @Test
        @DisplayName("2층: 역할/스킬 나열에는 라벨을 붙인다 — 안 붙이면 뭘 나열한 건지 알 수 없다")
        void fallsBackToMetadataWithLabels() {
            TeamEmbedding embedding = embedding(null, List.of("BE"), List.of("Spring", "JPA"),
                    "주 2회 오프라인", true);

            assertThat(RecommendationSummaryFactory.teamSummary(embedding, emptyTeam()))
                    .isEqualTo("모집 역할: BE / 요구 스킬: Spring, JPA / 주 2회 오프라인 / 초보자 환영");
        }

        @Test
        @DisplayName("3층: 임베딩 행 자체가 없어도(null) 터지지 않고 teams 행으로 내려간다")
        void nullEmbeddingFallsBackToTeam() {
            Team team = new Team();
            team.setTitle("커머스 플랫폼 팀");
            team.setRole(List.of("백엔드 개발자"));
            team.setPromotionText("주 2회 모여서 만듭니다");

            assertThat(RecommendationSummaryFactory.teamSummary(null, team))
                    .isEqualTo("커머스 플랫폼 팀 / 모집 역할: 백엔드 개발자 / 주 2회 모여서 만듭니다");
        }

        @Test
        @DisplayName("4층: 조립할 게 하나도 없으면 빈 문자열")
        void emptyStringWhenNothingAvailable() {
            assertThat(RecommendationSummaryFactory.teamSummary(emptyEmbedding(), emptyTeam()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("beginner_friendly 는 true 일 때만 문장에 넣는다")
    class BeginnerFriendly {

        /** false 는 "초보자 환영 아님"이 아니다 — 부정 문구를 만들면 LLM 이 없는 배타성을 지어낸다. */
        @Test
        @DisplayName("false 면 아무 문구도 넣지 않는다")
        void falseAddsNothing() {
            TeamEmbedding embedding = embedding(null, List.of("BE"), null, null, false);

            assertThat(RecommendationSummaryFactory.teamSummary(embedding, emptyTeam()))
                    .isEqualTo("모집 역할: BE")
                    .doesNotContain("초보자");
        }

        /** null 은 "AI 가 소개글에서 못 읽어냄"이라 false 와도 다르다. */
        @Test
        @DisplayName("null 이어도 아무 문구도 넣지 않는다")
        void nullAddsNothing() {
            TeamEmbedding embedding = embedding(null, List.of("BE"), null, null, null);

            assertThat(RecommendationSummaryFactory.teamSummary(embedding, emptyTeam()))
                    .isEqualTo("모집 역할: BE")
                    .doesNotContain("초보자");
        }
    }

    // ── score_context ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("score_context")
    class ScoreContext {

        @Test
        @DisplayName("0~1 점수를 백분율로 풀고 label 을 이어 붙인다")
        void formatsScoreAndLabel() {
            assertThat(RecommendationSummaryFactory.scoreContext(0.88, 2, "BE 역할을 모집하고 있어요"))
                    .isEqualTo("적합도 88점(추천 2위). BE 역할을 모집하고 있어요");
        }

        @Test
        @DisplayName("label 이 없으면 점수만 남는다 (마침표를 흘리지 않는다)")
        void omitsMissingLabel() {
            assertThat(RecommendationSummaryFactory.scoreContext(0.9, 1, null))
                    .isEqualTo("적합도 90점(추천 1위)");
            assertThat(RecommendationSummaryFactory.scoreContext(0.9, 1, "   "))
                    .isEqualTo("적합도 90점(추천 1위)");
        }
    }
}
