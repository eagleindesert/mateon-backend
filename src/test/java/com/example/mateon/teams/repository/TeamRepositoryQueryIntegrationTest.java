package com.example.mateon.teams.repository;

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.teams.domain.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팀 리포지토리의 손으로 쓴 쿼리 둘을 실제 Postgres 에 대고 고정한다.
 *
 * <p>
 * {@code findEndedEventTeamsNotCompleted} 는 자동 종료 배치의 후보 선정 전부다. 조건이
 * 틀리면 배치는 매일 멀쩡히 돌면서 아무 팀도 종료하지 않거나, 반대로 아직 진행 중인 팀을
 * 닫아 평가 창을 열어 버린다. 서비스 테스트는 이 쿼리를 목으로 두므로 어느 쪽도 거기서는
 * 드러나지 않는다.
 *
 * <p>
 * {@code findByEventCategory} 는 네이티브 SQL 이라 컨텍스트 기동 시 문법 검증조차 받지
 * 않는다. 컬럼이 enum 의 <b>이름</b>(CONTEST)을 저장한다는 사실도 여기서 못박는다.
 */
class TeamRepositoryQueryIntegrationTest extends IntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);

    @Autowired
    TeamRepository teamRepository;
    @Autowired
    EventRepository eventRepository;

    @Nested
    @DisplayName("findEndedEventTeamsNotCompleted — 자동 종료 후보")
    class EndedEventTeams {

        @Test
        @DisplayName("공모전이 어제 끝났고 아직 종료되지 않은 팀이 후보다")
        void includesTeamWhoseEventEndedYesterday() {
            Team team = newTeam(newEvent(TODAY.minusDays(1)), null);

            assertThat(candidateIds()).containsExactly(team.getId());
        }

        @Test
        @DisplayName("오늘 끝나는 공모전은 아직 후보가 아니다 (경계는 미만이다)")
        void excludesEventEndingToday() {
            newTeam(newEvent(TODAY), null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("이미 종료된 팀은 다시 잡히지 않는다 (배치가 멱등인 이유)")
        void excludesAlreadyEndedTeam() {
            newTeam(newEvent(TODAY.minusDays(1)), LocalDateTime.of(2026, 9, 3, 9, 0));

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("자율 프로젝트 팀은 종료 기준 날짜가 없어 잡히지 않는다")
        void excludesStandaloneTeam() {
            newTeam(null, null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("마감일이 없는 공모전의 팀도 잡히지 않는다")
        void excludesEventWithoutEndDate() {
            newTeam(newEvent((LocalDate) null), null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("모집을 이미 닫은 팀도 공모전이 끝났으면 후보다 (모집 여부는 보지 않는다)")
        void recruitingFlagDoesNotMatter() {
            Team team = newTeam(newEvent(TODAY.minusDays(1)), null);
            team.setIsRecruiting(false);
            teamRepository.saveAndFlush(team);

            assertThat(candidateIds()).containsExactly(team.getId());
        }

        private List<Long> candidateIds() {
            return teamRepository.findEndedEventTeamsNotCompleted(TODAY).stream()
              .map(Team::getId)
              .toList();
        }
    }

    @Nested
    @DisplayName("findByEventCategory — 네이티브 조인")
    class ByEventCategory {

        @Test
        @DisplayName("그 카테고리 활동에 연결된 모집 중 팀만 나온다")
        void filtersByCategoryAndRecruiting() {
            Team contest = newTeam(newEvent(Category.CONTEST), null);
            newTeam(newEvent(Category.EXTERNAL), null);
            newTeam(null, null);
            Team closed = newTeam(newEvent(Category.CONTEST), null);
            closed.setIsRecruiting(false);
            teamRepository.saveAndFlush(closed);

            assertThat(teamRepository.findByEventCategory(Category.CONTEST.name()))
              .extracting(Team::getId)
              .containsExactly(contest.getId());
        }

        /**
         * 리포지토리 단의 사실을 남겨 둔다. 이 때문에 {@code TeamService.resolveCategory} 가
         * 여기 도달하기 전에 enum 이름이 아닌 값을 400 으로 막는다 — 막지 않으면 라벨이
         * 에러 없이 빈 목록으로 나간다.
         */
        @Test
        @DisplayName("컬럼은 enum 이름을 저장하므로 한글 라벨로는 아무것도 잡히지 않는다")
        void labelDoesNotMatchStoredName() {
            newTeam(newEvent(Category.CONTEST), null);

            assertThat(teamRepository.findByEventCategory(Category.CONTEST.getLabel())).isEmpty();
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private Event newEvent(LocalDate endDate) {
        Event event = baseEvent(Category.CONTEST);
        event.setEndDate(endDate);
        return eventRepository.saveAndFlush(event);
    }

    private Event newEvent(Category category) {
        return eventRepository.saveAndFlush(baseEvent(category));
    }

    private Event baseEvent(Category category) {
        Event event = new Event();
        event.setCategory(category);
        event.setField(Field.ETC);
        event.setTitle("쿼리 테스트 활동");
        return event;
    }

    private Team newTeam(Event event, LocalDateTime endedAt) {
        Team team = new Team();
        team.setTitle("쿼리 테스트 팀");
        team.setEventId(event == null ? null : event.getId());
        team.setLeaderUserId(1L);
        team.setCapacity(4);
        team.setIsRecruiting(true);
        team.setEndedAt(endedAt);
        return teamRepository.saveAndFlush(team);
    }
}
