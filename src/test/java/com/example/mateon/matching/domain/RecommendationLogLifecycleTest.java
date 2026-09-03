package com.example.mateon.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 로그/아이템 persist 콜백이 createdAt 을 채우는지 고정한다.
 *
 * <p>
 * 운영 저장은 LiveTest 경로라 관문 커버리지에 안 잡힌다. 콜백 자체는 Hibernate 가
 * 부르는 한 줄이라 여기서 직접 호출한다.
 */
class RecommendationLogLifecycleTest {

    @Test
    @DisplayName("유저→팀 로그와 아이템이 persist 때 시각을 채운다")
    void userToTeamStampsCreatedAt() {
        UserToTeamRecommendationLog log = new UserToTeamRecommendationLog(1L, 2L, 3, 3);
        log.addItem(10L, 1, 0.9, "GOOD", null);
        log.onCreate();
        log.getItems().get(0).onCreate();

        assertThat(log.getCreatedAt()).isNotNull();
        assertThat(log.getItems().get(0).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("팀→유저 로그와 아이템이 persist 때 시각을 채운다")
    void teamToUserStampsCreatedAt() {
        TeamToUserRecommendationLog log = new TeamToUserRecommendationLog(1L, 2L, 3, 3);
        log.addItem(10L, 1, 0.9, "GOOD", null);
        log.onCreate();
        log.getItems().get(0).onCreate();

        assertThat(log.getCreatedAt()).isNotNull();
        assertThat(log.getItems().get(0).getCreatedAt()).isNotNull();
    }
}
