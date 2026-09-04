package com.example.mateon.matching.repository;

import com.example.mateon.matching.domain.TeamToUserRecommendationItem;
import com.example.mateon.matching.domain.TeamToUserRecommendationLog;
import com.example.mateon.matching.domain.UserToTeamRecommendationItem;
import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 로그 리포지토리의 손으로 쓴 JPQL 을 실제 Postgres 에 대고 고정한다. 양방향이 같은
 * 규약이라 한 클래스에서 나란히 본다.
 *
 * <p>
 * 이 쿼리들은 서비스 테스트에서 전부 목으로 대체된다. 그런데 틀렸을 때의 증상이 조용하다 —
 * {@code findShownItems} 가 노출 수로 자르지 못하면 사용자가 본 적 없는 후보까지 "안 골랐다"로
 * AI 에 보고되고, {@code findLatestItem} 이 옛 로그를 집으면 제안에 낡은 점수가 스냅샷된다.
 * 어느 쪽도 화면에는 아무 표시가 없다.
 */
class RecommendationLogRepositoryQueryIntegrationTest extends IntegrationTestBase {

    private static final long TEAM_ID = 42L;
    private static final LocalDateTime SELECTED_AT = LocalDateTime.of(2026, 9, 4, 12, 0, 0);

    @Autowired
    UserToTeamRecommendationLogRepository userToTeam;
    @Autowired
    TeamToUserRecommendationLogRepository teamToUser;
    @Autowired
    UserRepository userRepository;
    @Autowired
    EntityManager entityManager;

    /**
     * 유저→팀 로그의 user_id 는 users 에 외래키가 걸려 있어(V9) 실제 사용자가 필요하다.
     * 팀→유저 로그의 team_id 와 아이템의 user_id 는 외래키가 없어 숫자만으로 충분하다.
     */
    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        userId = newUser("나");
        otherUserId = newUser("남");
    }

    @Nested
    @DisplayName("findShownItems — 화면에 노출된 만큼만, 순위순으로")
    class FindShownItems {

        @Test
        @DisplayName("유저→팀: shownCount 까지만 rankNo 오름차순으로 나온다")
        void userToTeamCutsAtShownCount() {
            UserToTeamRecommendationLog log = new UserToTeamRecommendationLog(userId, null, 4, 2);
            log.addItem(103L, 3, 0.7, "c", null);
            log.addItem(101L, 1, 0.9, "a", null);
            log.addItem(104L, 4, 0.6, "d", null);
            log.addItem(102L, 2, 0.8, "b", null);
            Long logId = userToTeam.save(log).getId();
            endRequest();

            List<UserToTeamRecommendationItem> shown = userToTeam.findShownItems(logId);

            assertThat(shown).extracting(UserToTeamRecommendationItem::getTeamId)
              .containsExactly(101L, 102L);
        }

        @Test
        @DisplayName("유저→팀: shownCount 가 null 이면 (V32 이전 행) 전체가 나온다")
        void userToTeamNullShownCountReturnsAll() {
            UserToTeamRecommendationLog log = new UserToTeamRecommendationLog(userId, null, 3, null);
            log.addItem(102L, 2, 0.8, "b", null);
            log.addItem(101L, 1, 0.9, "a", null);
            log.addItem(103L, 3, 0.7, "c", null);
            Long logId = userToTeam.save(log).getId();
            endRequest();

            assertThat(userToTeam.findShownItems(logId))
              .extracting(UserToTeamRecommendationItem::getTeamId)
              .containsExactly(101L, 102L, 103L);
        }

        @Test
        @DisplayName("유저→팀: 다른 로그의 아이템은 섞이지 않는다")
        void userToTeamScopedToLog() {
            UserToTeamRecommendationLog mine = new UserToTeamRecommendationLog(userId, null, 1, 5);
            mine.addItem(101L, 1, 0.9, "a", null);
            Long logId = userToTeam.save(mine).getId();
            UserToTeamRecommendationLog other = new UserToTeamRecommendationLog(userId, null, 1, 5);
            other.addItem(201L, 1, 0.9, "z", null);
            userToTeam.save(other);
            endRequest();

            assertThat(userToTeam.findShownItems(logId))
              .extracting(UserToTeamRecommendationItem::getTeamId)
              .containsExactly(101L);
        }

        @Test
        @DisplayName("팀→유저: 같은 규약이다")
        void teamToUserCutsAtShownCount() {
            TeamToUserRecommendationLog log = new TeamToUserRecommendationLog(TEAM_ID, userId, 3, 2);
            log.addItem(13L, 3, 0.7, "c", null);
            log.addItem(11L, 1, 0.9, "a", null);
            log.addItem(12L, 2, 0.8, "b", null);
            Long logId = teamToUser.save(log).getId();
            endRequest();

            assertThat(teamToUser.findShownItems(logId))
              .extracting(TeamToUserRecommendationItem::getUserId)
              .containsExactly(11L, 12L);
        }
    }

    @Nested
    @DisplayName("findLatestItem — 같은 쌍의 가장 최근 로그")
    class FindLatestItem {

        @Test
        @DisplayName("유저→팀: 나중 로그의 아이템이 이긴다 (순위가 더 낮아도)")
        void userToTeamPrefersNewerLog() {
            UserToTeamRecommendationLog older = new UserToTeamRecommendationLog(userId, null, 1, 1);
            older.addItem(TEAM_ID, 1, 0.9, "old", null);
            userToTeam.save(older);
            UserToTeamRecommendationLog newer = new UserToTeamRecommendationLog(userId, null, 3, 3);
            newer.addItem(TEAM_ID, 3, 0.5, "new", null);
            Long newerId = userToTeam.save(newer).getId();
            endRequest();

            UserToTeamRecommendationItem latest = userToTeam.findLatestItem(userId, TEAM_ID).orElseThrow();

            assertThat(latest.getLog().getId()).isEqualTo(newerId);
            assertThat(latest.getLabel()).isEqualTo("new");
        }

        @Test
        @DisplayName("유저→팀: 추천받은 적 없는 팀이면 비어 있다 (다른 유저의 로그는 세지 않는다)")
        void userToTeamEmptyWhenNeverRecommended() {
            UserToTeamRecommendationLog someoneElse = new UserToTeamRecommendationLog(otherUserId, null, 1, 1);
            someoneElse.addItem(TEAM_ID, 1, 0.9, "x", null);
            userToTeam.save(someoneElse);
            endRequest();

            assertThat(userToTeam.findLatestItem(userId, TEAM_ID)).isEmpty();
        }

        @Test
        @DisplayName("팀→유저: 같은 규약이다")
        void teamToUserPrefersNewerLog() {
            TeamToUserRecommendationLog older = new TeamToUserRecommendationLog(TEAM_ID, 7L, 1, 1);
            older.addItem(userId, 1, 0.9, "old", null);
            teamToUser.save(older);
            TeamToUserRecommendationLog newer = new TeamToUserRecommendationLog(TEAM_ID, 7L, 1, 1);
            newer.addItem(userId, 1, 0.4, "new", null);
            Long newerId = teamToUser.save(newer).getId();
            endRequest();

            TeamToUserRecommendationItem latest = teamToUser.findLatestItem(TEAM_ID, userId).orElseThrow();

            assertThat(latest.getLog().getId()).isEqualTo(newerId);
            assertThat(latest.getLabel()).isEqualTo("new");
        }
    }

    @Nested
    @DisplayName("markSelected / updateReason — 그 아이템만 고친다")
    class BulkUpdates {

        @Test
        @DisplayName("유저→팀: 선택 표시는 해당 아이템에만 붙고 옆 아이템은 그대로다")
        void userToTeamMarkSelected() {
            UserToTeamRecommendationLog log = new UserToTeamRecommendationLog(userId, null, 2, 2);
            log.addItem(101L, 1, 0.9, "a", null);
            log.addItem(102L, 2, 0.8, "b", null);
            userToTeam.save(log);
            Long selectedId = log.getItems().get(0).getId();
            Long untouchedId = log.getItems().get(1).getId();
            endRequest();

            int updated = userToTeam.markSelected(selectedId, SELECTED_AT);

            assertThat(updated).isEqualTo(1);
            assertThat(entityManager.find(UserToTeamRecommendationItem.class, selectedId).getSelectedAt())
              .isEqualTo(SELECTED_AT);
            assertThat(entityManager.find(UserToTeamRecommendationItem.class, untouchedId).getSelectedAt())
              .isNull();
        }

        @Test
        @DisplayName("유저→팀: 이유 캐시도 해당 아이템에만 쓴다")
        void userToTeamUpdateReason() {
            UserToTeamRecommendationLog log = new UserToTeamRecommendationLog(userId, null, 2, 2);
            log.addItem(101L, 1, 0.9, "a", null);
            log.addItem(102L, 2, 0.8, "b", null);
            userToTeam.save(log);
            Long targetId = log.getItems().get(0).getId();
            Long untouchedId = log.getItems().get(1).getId();
            endRequest();

            int updated = userToTeam.updateReason(targetId, "기술 스택이 맞습니다.");

            assertThat(updated).isEqualTo(1);
            assertThat(entityManager.find(UserToTeamRecommendationItem.class, targetId).getReason())
              .isEqualTo("기술 스택이 맞습니다.");
            assertThat(entityManager.find(UserToTeamRecommendationItem.class, untouchedId).getReason())
              .isNull();
        }

        @Test
        @DisplayName("없는 아이템은 0 행이다 (양방향)")
        void unknownItemUpdatesNothing() {
            assertThat(userToTeam.markSelected(-1L, SELECTED_AT)).isZero();
            assertThat(userToTeam.updateReason(-1L, "x")).isZero();
            assertThat(teamToUser.markSelected(-1L, SELECTED_AT)).isZero();
            assertThat(teamToUser.updateReason(-1L, "x")).isZero();
        }

        @Test
        @DisplayName("팀→유저: 같은 규약이다")
        void teamToUserBulkUpdates() {
            TeamToUserRecommendationLog log = new TeamToUserRecommendationLog(TEAM_ID, 7L, 2, 2);
            log.addItem(11L, 1, 0.9, "a", null);
            log.addItem(12L, 2, 0.8, "b", null);
            teamToUser.save(log);
            Long targetId = log.getItems().get(0).getId();
            Long untouchedId = log.getItems().get(1).getId();
            endRequest();

            assertThat(teamToUser.markSelected(targetId, SELECTED_AT)).isEqualTo(1);
            assertThat(teamToUser.updateReason(targetId, "협업 스타일이 맞습니다.")).isEqualTo(1);

            TeamToUserRecommendationItem target = entityManager.find(TeamToUserRecommendationItem.class, targetId);
            TeamToUserRecommendationItem untouched = entityManager.find(TeamToUserRecommendationItem.class, untouchedId);
            assertThat(target.getSelectedAt()).isEqualTo(SELECTED_AT);
            assertThat(target.getReason()).isEqualTo("협업 스타일이 맞습니다.");
            assertThat(untouched.getSelectedAt()).isNull();
            assertThat(untouched.getReason()).isNull();
        }
    }

    // --- 하네스 -------------------------------------------------------------

    /**
     * flush 로 실제 SQL 을 내보내고 clear 로 1차 캐시를 비운다. 이걸 안 하면 방금 만든 객체를
     * 그대로 돌려받아 쿼리가 DB 에서 무엇을 읽었는지 확인하지 못한다.
     */
    private void endRequest() {
        entityManager.flush();
        entityManager.clear();
    }

    // --- 픽스처 -------------------------------------------------------------

    private long newUser(String name) {
        return userRepository.save(User.builder()
          .email(UUID.randomUUID() + "@test.ac.kr")
          .name(name)
          .build()).getId();
    }
}
