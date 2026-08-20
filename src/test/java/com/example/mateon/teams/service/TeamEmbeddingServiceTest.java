package com.example.mateon.teams.service;

import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.teams.client.TeamEmbeddingClient;
import com.example.mateon.teams.client.TeamEmbeddingRefreshRequest;
import com.example.mateon.teams.client.TeamEmbeddingRefreshResponse;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import com.example.mateon.teams.domain.TeamEmbeddingRefreshStatus;
import com.example.mateon.teams.repository.TeamEmbeddingRepository;
import com.example.mateon.teams.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 팀 임베딩 갱신의 경합 규약을 고정한다.
 *
 * <p>
 * 이 테스트가 생긴 계기: 팀 생성과 수정이 각각 비동기 갱신을 띄우는데, 둘이 동시에 돌면서
 * <b>AI 응답이 늦게 온 쪽이 무조건 이겼다</b>. 실제로 짧은 수정 텍스트의 결과가 먼저 저장되고
 * 0.3초 뒤 도착한 긴 생성 텍스트의 결과가 그 위를 덮어, 수정한 지 한참 지난 팀의 임베딩이
 * 생성 시점 내용으로 남았다. 로그에는 "저장 완료"가 두 번 찍혀 성공처럼 보였기 때문에
 * DB 를 직접 들여다보기 전까지 드러나지 않았다.
 *
 * <p>
 * 그래서 여기서 단정하는 것은 "저장에 성공했는가"가 아니라 <b>어느 결과가 남는가</b>다.
 * 순서를 보장하는 대신, 결과마다 그것이 반영하는 팀의 시점(source_updated_at)을 들고 다니게 해
 * 낡은 결과를 버린다. 시간 의존적 경합은 스레드로 재현하면 불안정해지므로, 도착 순서를
 * "행에 이미 저장된 시점"으로 표현해 결정적으로 검증한다.
 */
class TeamEmbeddingServiceTest {

    private static final long TEAM_ID = 21L;

    /**
     * 팀에 연결된 공모전(활동). 자율 프로젝트면 팀의 eventId 가 null 이다.
     */
    private static final long EVENT_ID = 7L;

    /**
     * 실제 컬럼은 vector(1536)지만, 여기서 검증하는 건 차원 값 자체가 아니라 판정 로직이다.
     */
    private static final int DIMENSION = 4;

    /**
     * 팀 생성 시점. 이 시점을 반영하는 결과가 "낡은 결과"다.
     */
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 7, 30, 2, 3, 50);

    /**
     * 팀 수정 시점. 이 시점을 반영하는 결과가 남아야 한다.
     */
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 7, 30, 2, 3, 51);

    private TeamRepository teamRepository;
    private EventRepository eventRepository;
    private TeamEmbeddingRepository teamEmbeddingRepository;
    private TeamEmbeddingClient client;
    private TeamEmbeddingService service;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        eventRepository = mock(EventRepository.class);
        teamEmbeddingRepository = mock(TeamEmbeddingRepository.class);
        client = mock(TeamEmbeddingClient.class);

        AiServerProperties properties = mock(AiServerProperties.class);
        when(properties.getEmbeddingDimension()).thenReturn(DIMENSION);

        service = new TeamEmbeddingService(teamRepository, eventRepository,
          teamEmbeddingRepository, client, properties);
    }

    @Nested
    @DisplayName("낡은 결과 폐기 (last-write-wins 차단)")
    class StaleResultDiscard {

        @Test
        @DisplayName("생성 시점 결과가 늦게 도착해도 수정 시점 결과를 덮지 않는다")
        void discardsResultOlderThanStoredRow() {
            // 수정 갱신이 이미 끝나 행이 UPDATED_AT 을 반영 중이고,
            // 이제서야 생성 갱신(CREATED_AT 기준)의 AI 응답이 도착한 상황.
            givenTeam(CREATED_AT);
            givenStoredRow(UPDATED_AT, List.of("Java", "Redis"));
            givenAiResponse(List.of("Spring Boot", "PostgreSQL"));

            service.refresh(TEAM_ID);

            verify(teamEmbeddingRepository, never()).save(any());
        }

        @Test
        @DisplayName("수정 시점 결과는 저장되고 행의 기준 시점도 함께 올라간다")
        void savesResultNewerThanStoredRow() {
            givenTeam(UPDATED_AT);
            givenStoredRow(CREATED_AT, List.of("Spring Boot", "PostgreSQL"));
            givenAiResponse(List.of("Java", "Redis"));

            service.refresh(TEAM_ID);

            TeamEmbedding saved = captureSaved();
            assertThat(saved.getRequiredSkills()).containsExactly("Java", "Redis");
            // 기준 시점을 같이 올리지 않으면 다음 결과가 낡음 판정을 통과해 버린다.
            assertThat(saved.getSourceUpdatedAt()).isEqualTo(UPDATED_AT);
            assertThat(saved.getRefreshStatus()).isEqualTo(TeamEmbeddingRefreshStatus.SUCCESS);
        }

        @Test
        @DisplayName("행이 없던 첫 갱신은 그대로 저장된다")
        void savesFirstRefreshWhenRowAbsent() {
            givenTeam(CREATED_AT);
            when(teamEmbeddingRepository.findById(TEAM_ID)).thenReturn(Optional.empty());
            givenAiResponse(List.of("Spring Boot", "PostgreSQL"));

            service.refresh(TEAM_ID);

            assertThat(captureSaved().getSourceUpdatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("같은 시점 결과는 버리지 않는다 (재시도·멱등 재계산 허용)")
        void savesResultWithSameTimestamp() {
            givenTeam(UPDATED_AT);
            givenStoredRow(UPDATED_AT, List.of("Java", "Redis"));
            givenAiResponse(List.of("Java", "Redis"));

            service.refresh(TEAM_ID);

            verify(teamEmbeddingRepository).save(any());
        }

        @Test
        @DisplayName("V26 이전 행(기준 시점 없음)은 판정 불가라 버리지 않는다")
        void savesWhenStoredTimestampUnknown() {
            givenTeam(CREATED_AT);
            givenStoredRow(null, List.of("Spring Boot", "PostgreSQL"));
            givenAiResponse(List.of("Java", "Redis"));

            service.refresh(TEAM_ID);

            assertThat(captureSaved().getSourceUpdatedAt()).isEqualTo(CREATED_AT);
        }
    }

    @Nested
    @DisplayName("실패 기록도 같은 판정을 따른다")
    class FailureRecording {

        @Test
        @DisplayName("실패는 행의 기준 시점을 올리지 않는다 (내용은 여전히 예전 것이므로)")
        void failureKeepsStoredTimestamp() {
            givenTeam(UPDATED_AT);
            givenStoredRow(CREATED_AT, List.of("Spring Boot", "PostgreSQL"));
            when(client.refresh(any())).thenThrow(new RuntimeException("AI 서버 다운"));

            assertThatThrownBy(() -> service.refresh(TEAM_ID)).isInstanceOf(RuntimeException.class);

            TeamEmbedding saved = captureSaved();
            assertThat(saved.getRefreshStatus()).isEqualTo(TeamEmbeddingRefreshStatus.FAILED);
            assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
            // 여기서 UPDATED_AT 을 적어 버리면, 뒤늦게 성공한 정상 결과가 낡은 것으로 오판된다.
            assertThat(saved.getSourceUpdatedAt()).isEqualTo(CREATED_AT);
            assertThat(saved.getRequiredSkills()).containsExactly("Spring Boot", "PostgreSQL");
        }

        @Test
        @DisplayName("낡은 갱신의 실패는 최신 행을 FAILED 로 오염시키지 않는다")
        void discardsFailureOlderThanStoredRow() {
            givenTeam(CREATED_AT);
            givenStoredRow(UPDATED_AT, List.of("Java", "Redis"));
            when(client.refresh(any())).thenThrow(new RuntimeException("AI 서버 다운"));

            assertThatThrownBy(() -> service.refresh(TEAM_ID)).isInstanceOf(RuntimeException.class);

            verify(teamEmbeddingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("판정과 저장 사이의 충돌")
    class SaveConflict {

        @Test
        @DisplayName("버전 충돌이 나면 다시 읽어 재판정한다 — 그 사이 최신 결과가 들어왔으면 폐기")
        void rereadsAndDiscardsAfterVersionConflict() {
            givenTeam(CREATED_AT);
            // 첫 읽기 때는 통과 가능한 행이었지만, 저장 직전에 수정 갱신이 끼어들어 버전이 밀렸다.
            when(teamEmbeddingRepository.findById(TEAM_ID))
              .thenReturn(Optional.of(row(CREATED_AT.minusSeconds(1), List.of("Spring Boot"))))
              .thenReturn(Optional.of(row(UPDATED_AT, List.of("Java", "Redis"))));
            when(teamEmbeddingRepository.save(any()))
              .thenThrow(new OptimisticLockingFailureException("버전 충돌"));
            givenAiResponse(List.of("Spring Boot", "PostgreSQL"));

            service.refresh(TEAM_ID);

            // 재시도에서 낡음이 드러나 두 번째 저장은 시도조차 하지 않는다. 예외도 새어 나가지 않는다.
            verify(teamEmbeddingRepository, times(1)).save(any());
        }
    }

    @Nested
    @DisplayName("AI 요청에 실리는 공모전 정보")
    class ContestField {

        @Test
        @DisplayName("연결된 공모전의 제목이 contest_field 로 실린다")
        void sendsEventTitleAsContestField() {
            givenTeam(CREATED_AT, EVENT_ID);
            givenEvent("2026 커머스 아이디어 공모전");
            givenAiResponse(List.of("Spring Boot", "PostgreSQL"));

            service.refresh(TEAM_ID);

            // 카테고리(CONTEST)의 한글 표기 "공모전"이 아니라 제목이어야 한다 — 분야 정보가
            // 담기는 곳이 제목이라는 것이 이 필드를 title 로 채우는 이유다.
            assertThat(captureRequest().getContestField()).isEqualTo("2026 커머스 아이디어 공모전");
        }

        @Test
        @DisplayName("intro_text 의 제목은 팀 제목이다 (공모전 제목과 섞이지 않는다)")
        void keepsTeamTitleInIntroText() {
            givenTeam(CREATED_AT, EVENT_ID);
            givenEvent("2026 커머스 아이디어 공모전");
            givenAiResponse(List.of("Spring Boot", "PostgreSQL"));

            service.refresh(TEAM_ID);

            assertThat(captureRequest().getIntroText()).startsWith("제목: 임베딩테스트 팀");
        }

        @Test
        @DisplayName("자율 프로젝트(eventId 없음)는 contest_field 가 null 이고 활동을 조회하지도 않는다")
        void sendsNullContestFieldWhenTeamHasNoEvent() {
            givenTeam(CREATED_AT, null);
            givenAiResponse(List.of("Spring Boot", "PostgreSQL"));

            service.refresh(TEAM_ID);

            assertThat(captureRequest().getContestField()).isNull();
            verifyNoInteractions(eventRepository);
        }

        @Test
        @DisplayName("연결된 활동이 사라졌어도 임베딩 갱신은 계속된다 (contest_field 만 null)")
        void sendsNullContestFieldWhenEventRowMissing() {
            givenTeam(CREATED_AT, EVENT_ID);
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
            when(teamEmbeddingRepository.findById(TEAM_ID)).thenReturn(Optional.empty());
            givenAiResponse(List.of("Spring Boot", "PostgreSQL"));

            service.refresh(TEAM_ID);

            assertThat(captureRequest().getContestField()).isNull();
            // 공모전 하나가 지워졌다고 그 팀들의 임베딩이 통째로 실패하면 안 된다.
            assertThat(captureSaved().getRefreshStatus()).isEqualTo(TeamEmbeddingRefreshStatus.SUCCESS);
        }
    }

    // ── 준비 헬퍼 ────────────────────────────────────────────────────────────
    private void givenTeam(LocalDateTime updatedAt) {
        givenTeam(updatedAt, null);
    }

    private void givenTeam(LocalDateTime updatedAt, Long eventId) {
        Team team = new Team();
        team.setId(TEAM_ID);
        team.setEventId(eventId);
        team.setTitle("임베딩테스트 팀");
        team.setPromotionText("커머스 플랫폼을 만드는 팀입니다.");
        team.setRole(List.of("BE"));
        team.setRequiredSkills(List.of("Spring Boot", "PostgreSQL"));
        team.setUpdatedAt(updatedAt);
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
    }

    /**
     * category/field 는 NOT NULL 컬럼이라 채우지만, contest_field 로 나가는 값은 title 뿐이다.
     */
    private void givenEvent(String title) {
        Event event = new Event();
        event.setId(EVENT_ID);
        event.setCategory(Event.Category.CONTEST);
        event.setField(Event.Field.PLANNING_IDEA);
        event.setTitle(title);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
    }

    private void givenStoredRow(LocalDateTime sourceUpdatedAt, List<String> requiredSkills) {
        when(teamEmbeddingRepository.findById(TEAM_ID))
          .thenReturn(Optional.of(row(sourceUpdatedAt, requiredSkills)));
    }

    private TeamEmbedding row(LocalDateTime sourceUpdatedAt, List<String> requiredSkills) {
        TeamEmbedding stored = new TeamEmbedding();
        stored.setTeamId(TEAM_ID);
        stored.setSourceUpdatedAt(sourceUpdatedAt);
        stored.setRequiredSkills(requiredSkills);
        return stored;
    }

    private void givenAiResponse(List<String> requiredSkills) {
        TeamEmbeddingRefreshResponse.Metadata metadata = new TeamEmbeddingRefreshResponse.Metadata();
        metadata.setRecruitingRoles(List.of("BE"));
        metadata.setRequiredSkills(requiredSkills);

        TeamEmbeddingRefreshResponse response = new TeamEmbeddingRefreshResponse();
        response.setMissingFields(List.of());
        response.setEmbeddingText("팀 소개: 커머스 플랫폼");
        response.setEmbeddingVector(new double[DIMENSION]);
        response.setMetadata(metadata);

        when(client.refresh(any(TeamEmbeddingRefreshRequest.class))).thenReturn(response);
    }

    private TeamEmbeddingRefreshRequest captureRequest() {
        ArgumentCaptor<TeamEmbeddingRefreshRequest> captor
          = ArgumentCaptor.forClass(TeamEmbeddingRefreshRequest.class);
        verify(client).refresh(captor.capture());
        return captor.getValue();
    }

    private TeamEmbedding captureSaved() {
        ArgumentCaptor<TeamEmbedding> captor = ArgumentCaptor.forClass(TeamEmbedding.class);
        verify(teamEmbeddingRepository).save(captor.capture());
        return captor.getValue();
    }
}
