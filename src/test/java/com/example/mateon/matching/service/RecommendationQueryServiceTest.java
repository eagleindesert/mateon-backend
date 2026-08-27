package com.example.mateon.matching.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.domain.SelectionDirection;
import com.example.mateon.matching.domain.TeamToUserRecommendationItem;
import com.example.mateon.matching.domain.TeamToUserRecommendationLog;
import com.example.mateon.matching.domain.UserToTeamRecommendationItem;
import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import com.example.mateon.matching.dto.snapshot.ProposalSnapshot;
import com.example.mateon.matching.dto.snapshot.ReasonSnapshot;
import com.example.mateon.matching.dto.snapshot.RecommendationSnapshot;
import com.example.mateon.matching.dto.snapshot.SelectionSnapshot;
import com.example.mateon.matching.dto.snapshot.TeamDisplayInfo;
import com.example.mateon.matching.dto.snapshot.UserDisplayInfo;
import com.example.mateon.matching.dto.snapshot.UserRecommendationSnapshot;
import com.example.mateon.matching.repository.MatchingIntentSlotRepository;
import com.example.mateon.matching.repository.TeamToUserRecommendationLogRepository;
import com.example.mateon.matching.repository.UserToTeamRecommendationLogRepository;
import com.example.mateon.support.TestEntities;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamEmbeddingRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamOfferRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import com.example.mateon.user.domain.UserEmbedding;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.user.repository.UserEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 추천의 <b>후보 선정 규칙</b>을 고정한다. 레포에서 가장 큰 클래스이자, 틀려도 에러가 나지 않는
 * 코드가 가장 많이 모인 곳이다 — 추천은 "이상한 결과"로만 드러나고 스택트레이스가 없다.
 *
 * <p>여기서 붙잡는 것들:
 *
 * <ul>
 *   <li><b>임베딩 행이 있어도 벡터가 null 이면 후보가 아니다.</b> V10 부터 갱신 실패도 행으로
 *       기록하기 때문에 "행이 있다 ≠ 쓸 수 있다"가 됐다. 이 필터가 빠지면 null 벡터가 AI 로
 *       나가 422 를 받는다.</li>
 *   <li><b>상한 200 에서 무엇이 잘리는지가 결정적이어야 한다.</b> 정렬 없이 자르면 매 요청마다
 *       다른 팀이 빠져 "어제는 보이던 팀이 오늘은 없다"가 된다.</li>
 *   <li><b>권한 검사가 조회보다 먼저다.</b> 팀장이 아닌 사람이 역제안을 요청했을 때 임베딩
 *       조회나 로그 조회가 먼저 일어나면, 404/400 의 차이만으로 남의 팀 상태를 알아낼 수 있다.</li>
 *   <li><b>캐시가 있으면 아무것도 더 읽지 않는다.</b> 조기 반환이 사라져도 결과는 같아서
 *       테스트 없이는 성능 회귀를 알 수 없다.</li>
 *   <li><b>두 방향에서 candidate/target 요약이 서로 뒤바뀌어 들어간다.</b> 이 도메인에서 가장
 *       조용히 깨질 지점 — 뒤집혀도 AI 는 그럴듯한 문장을 만들어 준다.</li>
 * </ul>
 */
class RecommendationQueryServiceTest {

    private static final long USER_ID = 1L;
    private static final long TEAM_ID = 100L;

    private UserEmbeddingRepository userEmbeddingRepository;
    private MatchingIntentSlotRepository slotRepository;
    private TeamRepository teamRepository;
    private TeamEmbeddingRepository teamEmbeddingRepository;
    private TeamApplicationRepository teamApplicationRepository;
    private TeamMemberRepository teamMemberRepository;
    private TeamOfferRepository teamOfferRepository;
    private EventRepository eventRepository;
    private UserCollaborationScoreRepository collaborationScoreRepository;
    private UserToTeamRecommendationLogRepository userToTeamLogRepository;
    private TeamToUserRecommendationLogRepository teamToUserLogRepository;

    private RecommendationQueryService service;

    @BeforeEach
    void setUp() {
        userEmbeddingRepository = mock(UserEmbeddingRepository.class);
        slotRepository = mock(MatchingIntentSlotRepository.class);
        teamRepository = mock(TeamRepository.class);
        teamEmbeddingRepository = mock(TeamEmbeddingRepository.class);
        teamApplicationRepository = mock(TeamApplicationRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        teamOfferRepository = mock(TeamOfferRepository.class);
        eventRepository = mock(EventRepository.class);
        collaborationScoreRepository = mock(UserCollaborationScoreRepository.class);
        userToTeamLogRepository = mock(UserToTeamRecommendationLogRepository.class);
        teamToUserLogRepository = mock(TeamToUserRecommendationLogRepository.class);

        service = new RecommendationQueryService(userEmbeddingRepository, slotRepository,
                teamRepository, teamEmbeddingRepository, teamApplicationRepository,
                teamMemberRepository, teamOfferRepository, eventRepository,
                collaborationScoreRepository, userToTeamLogRepository, teamToUserLogRepository);
    }

    @Nested
    @DisplayName("gather — 유저에게 맞는 팀 후보 모으기")
    class Gather {

        @Test
        @DisplayName("임베딩이 없으면 MATCHING_INTENT_REQUIRED (의도 추출부터 하라는 뜻)")
        void missingUserEmbedding() {
            when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.gather(USER_ID, null))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MATCHING_INTENT_REQUIRED);
        }

        @Test
        @DisplayName("슬롯이 없어도 같은 에러다 — 사용자가 할 일은 어느 쪽이든 '의도 추출 완료' 하나다")
        void missingSlotGivesSameError() {
            when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.of(userEmbedding()));
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.gather(USER_ID, null))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MATCHING_INTENT_REQUIRED);
        }

        @Test
        @DisplayName("eventId 를 주면 그 활동의 팀만, 없으면 모집 중 전체를 후보로 삼는다")
        void picksQueryByEventId() {
            givenQuerySide();
            when(teamRepository.findByEventIdAndIsRecruitingTrue(9L)).thenReturn(List.of());
            when(teamRepository.findByIsRecruitingTrue()).thenReturn(List.of());

            service.gather(USER_ID, 9L);
            verify(teamRepository).findByEventIdAndIsRecruitingTrue(9L);
            verify(teamRepository, never()).findByIsRecruitingTrue();

            service.gather(USER_ID, null);
            verify(teamRepository).findByIsRecruitingTrue();
        }

        @Test
        @DisplayName("행은 있는데 벡터가 null 인 팀은 후보에서 뺀다 (V10 의 실패 기록 행)")
        void excludesTeamsWithNullVector() {
            givenQuerySide();
            Team good = team(10L);
            Team broken = team(11L);
            when(teamRepository.findByIsRecruitingTrue()).thenReturn(List.of(good, broken));
            when(teamEmbeddingRepository.findAllById(anyList()))
                    .thenReturn(List.of(teamEmbedding(10L, new float[]{0.1f}), teamEmbedding(11L, null)));

            assertThat(service.gather(USER_ID, null).getCandidates())
                    .extracting(candidate -> candidate.getTeam().getId())
                    .containsExactly(10L);
        }

        @Test
        @DisplayName("내가 팀장인 팀과 이미 지원한 팀은 제외한다 (추천해도 지원할 수 없다)")
        void excludesMyTeamsAndApplications() {
            givenQuerySide();
            Team mine = team(10L);
            Team applied = team(11L);
            Team open = team(12L);
            // 목 픽스처는 반드시 바깥 when(...) 밖에서 만든다 — 스터빙 중에 또 스터빙하면
            // Mockito 가 UnfinishedStubbingException 을 던진다.
            var appliedApplication = applicationTo(applied);

            when(teamRepository.findByIsRecruitingTrue()).thenReturn(List.of(mine, applied, open));
            when(teamRepository.findByLeaderUserId(USER_ID)).thenReturn(List.of(mine));
            when(teamApplicationRepository.findByApplicantId(USER_ID))
                    .thenReturn(List.of(appliedApplication));
            when(teamEmbeddingRepository.findAllById(anyList()))
                    .thenReturn(List.of(teamEmbedding(12L, new float[]{0.1f})));

            assertThat(service.gather(USER_ID, null).getCandidates())
                    .extracting(candidate -> candidate.getTeam().getId())
                    .containsExactly(12L);
        }

        @Test
        @DisplayName("상한 200 에 걸리면 팀 id 내림차순으로 최신 200개만 남는다 (무엇이 잘리는지가 결정적이어야 한다)")
        void capsAtTwoHundredNewestFirst() {
            givenQuerySide();
            List<Team> teams = IntStream.rangeClosed(1, 205).mapToObj(i -> team((long) i)).toList();
            when(teamRepository.findByIsRecruitingTrue()).thenReturn(teams);
            when(teamEmbeddingRepository.findAllById(anyList())).thenReturn(
                    teams.stream().map(t -> teamEmbedding(t.getId(), new float[]{0.1f})).toList());

            List<Long> ids = service.gather(USER_ID, null).getCandidates().stream()
                    .map(candidate -> candidate.getTeam().getId())
                    .toList();

            assertThat(ids).hasSize(200);
            assertThat(ids.get(0)).isEqualTo(205L);
            assertThat(ids.get(199)).isEqualTo(6L);
            // 가장 오래된 다섯 팀이 잘려 나간다 — 매번 같은 팀이 잘려야 결과가 안정적이다.
            assertThat(ids).doesNotContain(1L, 2L, 3L, 4L, 5L);
        }

        @Test
        @DisplayName("질의 쪽 값은 사용자 임베딩과 슬롯에서 그대로 실려 나간다")
        void carriesQuerySide() {
            givenQuerySide();
            when(teamRepository.findByIsRecruitingTrue()).thenReturn(List.of());

            RecommendationSnapshot snapshot = service.gather(USER_ID, null);

            assertThat(snapshot.getQueryEmbedding()).containsExactly(0.5f, 0.5f);
            assertThat(snapshot.getDesiredRoles()).containsExactly("디자이너");
            assertThat(snapshot.getSkills()).containsExactly("Figma");
            assertThat(snapshot.getActivityStyle()).isEqualTo("온라인");
            assertThat(snapshot.getExperienceLevel()).isEqualTo("입문");
        }

        private void givenQuerySide() {
            when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.of(userEmbedding()));
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.of(slot(USER_ID)));
        }
    }

    @Nested
    @DisplayName("loadDisplayInfo — 팀당 반복 조회를 하지 않는다")
    class LoadDisplayInfo {

        @Test
        @DisplayName("빈 목록이면 리포지토리를 아예 부르지 않는다")
        void emptyShortCircuits() {
            assertThat(service.loadDisplayInfo(List.of(), Map.of())).isEmpty();

            verifyNoInteractions(eventRepository, teamMemberRepository);
        }

        @Test
        @DisplayName("활동은 팀 수와 무관하게 한 번에 조회한다 (N+1 금지)")
        void fetchesEventsInOneCall() {
            Team a = teamWithEvent(10L, 7L);
            Team b = teamWithEvent(11L, 7L);
            Team c = teamWithEvent(12L, 8L);
            when(eventRepository.findAllById(anyList())).thenReturn(List.of(event(7L), event(8L)));
            when(teamMemberRepository.countGroupedByTeamId(anyList())).thenReturn(List.of());

            service.loadDisplayInfo(List.of(10L, 11L, 12L), byId(a, b, c));

            verify(eventRepository, times(1)).findAllById(anyList());
            verify(teamMemberRepository, times(1)).countGroupedByTeamId(anyList());
        }

        @Test
        @DisplayName("자율 프로젝트(eventId=null)는 활동 없이도 정상 처리된다")
        void autonomousTeamHasNoEvent() {
            Team autonomous = teamWithEvent(10L, null);
            when(teamMemberRepository.countGroupedByTeamId(anyList())).thenReturn(List.of());

            Map<Long, TeamDisplayInfo> info = service.loadDisplayInfo(List.of(10L), byId(autonomous));

            assertThat(info.get(10L).getEvent()).isNull();
            verifyNoInteractions(eventRepository);
        }

        @Test
        @DisplayName("활동이 삭제됐으면 eventId 가 남아 있어도 null 로 준다")
        void deletedEventBecomesNull() {
            Team orphan = teamWithEvent(10L, 999L);
            when(eventRepository.findAllById(anyList())).thenReturn(List.of());
            when(teamMemberRepository.countGroupedByTeamId(anyList())).thenReturn(List.of());

            assertThat(service.loadDisplayInfo(List.of(10L), byId(orphan)).get(10L).getEvent()).isNull();
        }

        @Test
        @DisplayName("멤버 집계에 없는 팀은 0 명이다 (집계 결과에 아예 안 나온다)")
        void missingCountBecomesZero() {
            Team a = teamWithEvent(10L, null);
            Team b = teamWithEvent(11L, null);
            when(teamMemberRepository.countGroupedByTeamId(anyList())).thenReturn(List.of(count(10L, 3)));

            Map<Long, TeamDisplayInfo> info = service.loadDisplayInfo(List.of(10L, 11L), byId(a, b));

            assertThat(info.get(10L).getCurrentMemberCount()).isEqualTo(3);
            assertThat(info.get(11L).getCurrentMemberCount()).isZero();
        }
    }

    @Nested
    @DisplayName("gatherForTeam — 역제안. 권한 검사가 조회보다 먼저다")
    class GatherForTeam {

        @Test
        @DisplayName("없는 팀이면 RESOURCE_NOT_FOUND (400)")
        void unknownTeam() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.gatherForTeam(TEAM_ID, USER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("팀장이 아니면 임베딩을 조회하기 전에 막는다 — 외부인이 임베딩 준비 상태를 탐지하면 안 된다")
        void nonLeaderIsRejectedBeforeEmbeddingLookup() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamLedBy(TEAM_ID, 999L)));

            assertThatThrownBy(() -> service.gatherForTeam(TEAM_ID, USER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verifyNoInteractions(teamEmbeddingRepository);
        }

        @Test
        @DisplayName("팀 임베딩 벡터가 null 이면 TEAM_EMBEDDING_NOT_READY — 질의 자체가 불가능하다")
        void nullTeamVectorIsNotReady() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamLedBy(TEAM_ID, USER_ID)));
            when(teamEmbeddingRepository.findById(TEAM_ID))
                    .thenReturn(Optional.of(teamEmbedding(TEAM_ID, null)));

            assertThatThrownBy(() -> service.gatherForTeam(TEAM_ID, USER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.TEAM_EMBEDDING_NOT_READY);
        }

        @Test
        @DisplayName("이미 팀원·지원자·제안받은 사람은 후보에서 뺀다 (제안을 보낼 수 없는 상대다)")
        void excludesMembersApplicantsAndOffered() {
            givenTeamQuerySide();
            var existingMember = memberOf(2L);
            var applicant = applicationFrom(3L);

            when(slotRepository.findAllWithUser()).thenReturn(List.of(
                    slotOf(2L), slotOf(3L), slotOf(4L), slotOf(5L)));
            when(teamMemberRepository.findByTeamIdAndLeftAtIsNull(TEAM_ID))
                    .thenReturn(List.of(existingMember));
            when(teamApplicationRepository.findByTeamId(TEAM_ID))
                    .thenReturn(List.of(applicant));
            when(teamOfferRepository.findTargetUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(4L));
            when(userEmbeddingRepository.findAllById(anyList()))
                    .thenReturn(List.of(userEmbedding(5L)));

            assertThat(service.gatherForTeam(TEAM_ID, USER_ID).getCandidates())
                    .extracting(candidate -> candidate.getUser().getId())
                    .containsExactly(5L);
        }

        @Test
        @DisplayName("벡터가 없는 유저는 후보에서 뺀다 (없이 보내면 AI 가 422 를 준다)")
        void excludesUsersWithoutVector() {
            givenTeamQuerySide();
            when(slotRepository.findAllWithUser()).thenReturn(List.of(slotOf(2L), slotOf(3L)));
            when(userEmbeddingRepository.findAllById(anyList()))
                    .thenReturn(List.of(userEmbedding(2L), userEmbeddingWithoutVector(3L)));

            assertThat(service.gatherForTeam(TEAM_ID, USER_ID).getCandidates())
                    .extracting(candidate -> candidate.getUser().getId())
                    .containsExactly(2L);
        }

        @Test
        @DisplayName("상한 200 에 걸리면 유저 id 내림차순으로 최근 가입자 200명이 남는다")
        void capsAtTwoHundred() {
            givenTeamQuerySide();
            List<MatchingIntentSlot> slots = IntStream.rangeClosed(1, 205)
                    .mapToObj(i -> slotOf((long) i)).toList();
            when(slotRepository.findAllWithUser()).thenReturn(slots);
            when(userEmbeddingRepository.findAllById(anyList())).thenReturn(
                    IntStream.rangeClosed(1, 205).mapToObj(i -> userEmbedding((long) i)).toList());

            List<Long> ids = service.gatherForTeam(TEAM_ID, USER_ID).getCandidates().stream()
                    .map(candidate -> candidate.getUser().getId())
                    .toList();

            assertThat(ids).hasSize(200);
            assertThat(ids.get(0)).isEqualTo(205L);
            assertThat(ids).doesNotContain(1L, 2L, 3L, 4L, 5L);
        }

        @Test
        @DisplayName("질의 쪽 값은 팀 임베딩의 정규화 메타데이터에서 온다 (팀 컬럼 원본이 아니다)")
        void carriesTeamMetadata() {
            givenTeamQuerySide();
            when(slotRepository.findAllWithUser()).thenReturn(List.of());

            UserRecommendationSnapshot snapshot = service.gatherForTeam(TEAM_ID, USER_ID);

            assertThat(snapshot.getQueryEmbedding()).containsExactly(0.3f, 0.4f);
            assertThat(snapshot.getRecruitingRoles()).containsExactly("백엔드");
            assertThat(snapshot.getRequiredSkills()).containsExactly("Spring");
            assertThat(snapshot.getActivityStyle()).isEqualTo("오프라인");
            assertThat(snapshot.getBeginnerFriendly()).isTrue();
        }

        private void givenTeamQuerySide() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamLedBy(TEAM_ID, USER_ID)));

            TeamEmbedding embedding = teamEmbedding(TEAM_ID, new float[]{0.3f, 0.4f});
            embedding.setRecruitingRoles(List.of("백엔드"));
            embedding.setRequiredSkills(List.of("Spring"));
            embedding.setActivityStyle("오프라인");
            embedding.setBeginnerFriendly(true);
            when(teamEmbeddingRepository.findById(TEAM_ID)).thenReturn(Optional.of(embedding));
        }
    }

    @Nested
    @DisplayName("loadUserDisplayInfo")
    class LoadUserDisplayInfo {

        @Test
        @DisplayName("빈 목록이면 조회하지 않는다")
        void emptyShortCircuits() {
            assertThat(service.loadUserDisplayInfo(List.of(), Map.of())).isEmpty();

            verifyNoInteractions(collaborationScoreRepository);
        }

        @Test
        @DisplayName("평가를 한 번도 안 받은 유저는 기준점 36.5 로 채운다 (null 이 아니다)")
        void missingScoreFallsBackToInitial() {
            User rated = user(2L);
            User unrated = user(3L);
            var ratedScore = score(2L, new BigDecimal("42.0"));

            when(collaborationScoreRepository.findByUserIdIn(anyList()))
                    .thenReturn(List.of(ratedScore));

            Map<Long, UserDisplayInfo> info = service.loadUserDisplayInfo(
                    List.of(2L, 3L), Map.of(2L, rated, 3L, unrated));

            assertThat(info.get(2L).getCollaborationTemperature()).isEqualByComparingTo("42.0");
            assertThat(info.get(3L).getCollaborationTemperature())
                    .isEqualByComparingTo(CollaborationTemperatureCalculator.INITIAL);
        }
    }

    @Nested
    @DisplayName("상세 이유 재료 — 캐시가 있으면 더 읽지 않는다")
    class GatherReason {

        @Test
        @DisplayName("유저→팀: 캐시가 있으면 슬롯·팀·임베딩을 아예 조회하지 않는다")
        void cacheHitSkipsEverythingElse() {
            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID))
                    .thenReturn(Optional.of(userToTeamItem(0.9, "이미 만들어 둔 이유")));

            ReasonSnapshot snapshot = service.gatherReasonForUserToTeam(USER_ID, TEAM_ID);

            assertThat(snapshot.hasCachedReason()).isTrue();
            assertThat(snapshot.getCachedReason()).isEqualTo("이미 만들어 둔 이유");
            verifyNoInteractions(slotRepository, teamEmbeddingRepository);
            verify(teamRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("공백뿐인 캐시는 없는 것으로 본다 (빈 이유를 그대로 보여줄 수는 없다)")
        void blankCacheIsTreatedAsAbsent() {
            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID))
                    .thenReturn(Optional.of(userToTeamItem(0.9, "   ")));
            when(slotRepository.findByUserIdWithUser(USER_ID)).thenReturn(Optional.of(slot(USER_ID)));
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team(TEAM_ID)));
            when(teamEmbeddingRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

            ReasonSnapshot snapshot = service.gatherReasonForUserToTeam(USER_ID, TEAM_ID);

            assertThat(snapshot.hasCachedReason()).isFalse();
            assertThat(snapshot.getCandidateSummary()).isNotBlank();
            assertThat(snapshot.getTargetSummary()).isNotBlank();
        }

        @Test
        @DisplayName("추천받은 적 없는 팀의 이유를 요청하면 404 다 (준비 안 됨이 아니라 실제로 없다)")
        void unknownRecommendationIs404() {
            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.gatherReasonForUserToTeam(USER_ID, TEAM_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.RECOMMENDATION_NOT_FOUND);
        }

        @Test
        @DisplayName("팀→유저: 팀장 검증이 로그 조회보다 먼저다 — 404/200 차이로 남의 팀 이력을 흘리면 안 된다")
        void leaderCheckPrecedesLogLookup() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamLedBy(TEAM_ID, 999L)));

            assertThatThrownBy(() -> service.gatherReasonForTeamToUser(TEAM_ID, 2L, USER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verifyNoInteractions(teamToUserLogRepository);
        }

        @Test
        @DisplayName("팀→유저도 캐시가 있으면 조기 반환한다")
        void teamToUserCacheHit() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamLedBy(TEAM_ID, USER_ID)));
            when(teamToUserLogRepository.findLatestItem(TEAM_ID, 2L))
                    .thenReturn(Optional.of(teamToUserItem(0.8, "캐시된 이유")));

            assertThat(service.gatherReasonForTeamToUser(TEAM_ID, 2L, USER_ID).getCachedReason())
                    .isEqualTo("캐시된 이유");

            verifyNoInteractions(slotRepository);
        }
    }

    @Nested
    @DisplayName("제안 조립 재료 — 두 방향에서 요약이 서로 뒤바뀐다")
    class GatherProposal {

        @Test
        @DisplayName("유저→팀: candidate 는 팀 요약, target 은 내 요약이다")
        void userToTeamOrdering() {
            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID))
                    .thenReturn(Optional.of(userToTeamItem(0.91, null)));
            when(slotRepository.findByUserIdWithUser(USER_ID)).thenReturn(Optional.of(slot(USER_ID)));
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithEvent(TEAM_ID, 7L)));
            when(teamEmbeddingRepository.findById(TEAM_ID))
                    .thenReturn(Optional.of(summarizableTeamEmbedding()));

            ProposalSnapshot snapshot = service.gatherProposalForUserToTeam(USER_ID, TEAM_ID);

            assertThat(snapshot.getUserId()).isEqualTo(USER_ID);
            assertThat(snapshot.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(snapshot.getContestId()).isEqualTo(7L);
            assertThat(snapshot.getIntentId()).isEqualTo(USER_ID * 10);
            assertThat(snapshot.getSynergyScore()).isEqualTo(0.91);
            // 방향을 가르는 핵심: candidate 는 "상대(팀)", target 은 "나(유저)".
            assertThat(snapshot.getCandidateSummary()).isEqualTo("TEAM-SUMMARY");
            assertThat(snapshot.getTargetSummary()).isEqualTo("USER-SUMMARY");
        }

        @Test
        @DisplayName("팀→유저: candidate 와 target 이 정확히 반대로 들어간다")
        void teamToUserOrderingIsSwapped() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(myTeamWithEvent(TEAM_ID, 7L)));
            when(teamToUserLogRepository.findLatestItem(TEAM_ID, 2L))
                    .thenReturn(Optional.of(teamToUserItem(0.77, null)));
            when(slotRepository.findByUserIdWithUser(2L)).thenReturn(Optional.of(slot(2L)));
            when(teamEmbeddingRepository.findById(TEAM_ID))
                    .thenReturn(Optional.of(summarizableTeamEmbedding()));

            ProposalSnapshot snapshot = service.gatherProposalForTeamToUser(TEAM_ID, 2L, USER_ID);

            // userId 는 "그 유저" 다 — 요청자(팀장)가 아니라 제안 대상이다.
            assertThat(snapshot.getUserId()).isEqualTo(2L);
            assertThat(snapshot.getSynergyScore()).isEqualTo(0.77);
            // 유저→팀과 정확히 반대다.
            assertThat(snapshot.getCandidateSummary()).isEqualTo("USER-SUMMARY");
            assertThat(snapshot.getTargetSummary()).isEqualTo("TEAM-SUMMARY");
        }

        @Test
        @DisplayName("자율 프로젝트 팀이면 contestId 가 null 이다 (정상이다)")
        void autonomousTeamHasNullContestId() {
            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID))
                    .thenReturn(Optional.of(userToTeamItem(0.5, null)));
            when(slotRepository.findByUserIdWithUser(USER_ID)).thenReturn(Optional.of(slot(USER_ID)));
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithEvent(TEAM_ID, null)));
            when(teamEmbeddingRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

            assertThat(service.gatherProposalForUserToTeam(USER_ID, TEAM_ID).getContestId()).isNull();
        }

        @Test
        @DisplayName("추천 이력이 없으면 조립할 수 없다 — synergy_score 의 유일한 출처다")
        void withoutRecommendationThereIsNoScore() {
            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.gatherProposalForUserToTeam(USER_ID, TEAM_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.RECOMMENDATION_NOT_FOUND);
        }

        @Test
        @DisplayName("팀→유저도 팀장 검증이 먼저다")
        void teamToUserChecksLeaderFirst() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamLedBy(TEAM_ID, 999L)));

            assertThatThrownBy(() -> service.gatherProposalForTeamToUser(TEAM_ID, 2L, USER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verifyNoInteractions(teamToUserLogRepository);
        }
    }

    @Nested
    @DisplayName("선택 컨텍스트 수집 (선택 피드백)")
    class GatherSelection {

        @Test
        @DisplayName("추천 이력이 없으면 빈 결과다 — 추천을 안 거친 지원도 정상 경로다")
        void emptyWhenNeverRecommended() {
            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID))
              .thenReturn(Optional.empty());

            assertThat(service.gatherSelection(SelectionDirection.USER_TO_TEAM, USER_ID, TEAM_ID))
              .isEmpty();
        }

        @Test
        @DisplayName("노출됐던 후보만 담는다 (shown_count 로 자른 목록을 그대로 쓴다)")
        void carriesShownItemsOnly() {
            givenUserToTeamSelection();

            SelectionSnapshot snapshot = service.gatherSelection(
              SelectionDirection.USER_TO_TEAM, USER_ID, TEAM_ID).orElseThrow();

            assertThat(snapshot.getShownCandidates()).hasSize(2);
            assertThat(snapshot.getShownCandidates().get(0).getCandidateId()).isEqualTo(TEAM_ID);
            assertThat(snapshot.getShownCandidates().get(0).getComponentScores())
              .isEqualTo("{\"similarity\":0.8}");
            assertThat(snapshot.getSelectedItemId()).isEqualTo(500L);
            assertThat(snapshot.getSelectedCandidateId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("추천은 됐지만 화면에 안 뜬 후보(rank > shown_count)면 빈 결과다")
        void skipsCandidateThatWasNeverShown() {
            // 지난 추천에서 50위였던 팀에 목록을 직접 둘러보다 지원한 상황.
            // 이걸 선택으로 보내면 selected_candidate_id 가 shown_candidates 밖에 있게 된다.
            UserToTeamRecommendationItem unshown = userToTeamItem(0.11, null);
            TestEntities.withField(unshown, "rankNo", 50);
            TestEntities.withField(unshown, "log", userToTeamLog());   // shownCount = 2
            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID))
              .thenReturn(Optional.of(unshown));

            assertThat(service.gatherSelection(SelectionDirection.USER_TO_TEAM, USER_ID, TEAM_ID))
              .isEmpty();
            // 걸러졌으면 목록을 읽을 이유도 없다.
            verify(userToTeamLogRepository, never()).findShownItems(anyLong());
        }

        @Test
        @DisplayName("shown_count 가 없는 옛 로그(V32 이전)는 전부 노출된 것으로 본다")
        void legacyLogWithoutShownCountIsNotSkipped() {
            UserToTeamRecommendationItem item = userToTeamItem(0.11, null);
            TestEntities.withField(item, "rankNo", 50);
            UserToTeamRecommendationLog legacyLog
              = new UserToTeamRecommendationLog(USER_ID, null, 50, null);
            TestEntities.withField(item, "log", TestEntities.withId(legacyLog, 802L));

            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID))
              .thenReturn(Optional.of(item));
            when(userToTeamLogRepository.findShownItems(anyLong())).thenReturn(List.of(item));
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.of(slot(USER_ID)));

            assertThat(service.gatherSelection(SelectionDirection.USER_TO_TEAM, USER_ID, TEAM_ID))
              .isPresent();
        }

        @Test
        @DisplayName("유저 쪽 chooser_fields 는 슬롯에서 온다")
        void userChooserFieldsComeFromSlot() {
            givenUserToTeamSelection();

            SelectionSnapshot snapshot = service.gatherSelection(
              SelectionDirection.USER_TO_TEAM, USER_ID, TEAM_ID).orElseThrow();

            assertThat(snapshot.getChooserFields())
              .containsKeys("desired_roles", "experience_level");
        }

        @Test
        @DisplayName("슬롯이 사라졌어도 빈 chooser_fields 로 진행한다 (선택 자체는 기록해야 한다)")
        void missingSlotStillRecords() {
            givenUserToTeamSelection();
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            SelectionSnapshot snapshot = service.gatherSelection(
              SelectionDirection.USER_TO_TEAM, USER_ID, TEAM_ID).orElseThrow();

            assertThat(snapshot.getChooserFields()).isEmpty();
            assertThat(snapshot.getShownCandidates()).isNotEmpty();
        }

        @Test
        @DisplayName("팀 쪽 chooser_fields 는 모집 역할과 활동 분야다 (분야는 events 에서 온다)")
        void teamChooserFieldsMixTwoSources() {
            TeamToUserRecommendationItem item = teamToUserItem(0.9, null);
            TestEntities.withField(item, "log", teamToUserLog());
            when(teamToUserLogRepository.findLatestItem(TEAM_ID, 2L)).thenReturn(Optional.of(item));
            when(teamToUserLogRepository.findShownItems(anyLong())).thenReturn(List.of(item));

            TeamEmbedding embedding = teamEmbedding(TEAM_ID);
            embedding.setRecruitingRoles(List.of("BE"));
            when(teamEmbeddingRepository.findById(TEAM_ID)).thenReturn(Optional.of(embedding));

            Team team = team(TEAM_ID);
            team.setEventId(7L);
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
            when(eventRepository.findById(7L)).thenReturn(Optional.of(eventWithField()));

            SelectionSnapshot snapshot = service.gatherSelection(
              SelectionDirection.TEAM_TO_USER, TEAM_ID, 2L).orElseThrow();

            assertThat(snapshot.getChooserFields())
              .containsEntry("recruiting_roles", List.of("BE"))
              // enum 상수명 그대로여야 한다 — 한글 라벨을 보내면 AI 가 못 알아본다.
              .containsEntry("contest_field", "SCIENCE_ENGINEERING_TECH_IT");
        }

        private void givenUserToTeamSelection() {
            UserToTeamRecommendationItem selected = userToTeamItem(0.92, null);
            TestEntities.withField(selected, "log", userToTeamLog());
            TestEntities.withField(selected, "componentScores", "{\"similarity\":0.8}");

            UserToTeamRecommendationItem other = userToTeamItem(0.26, null);
            TestEntities.withId(other, 501L);
            TestEntities.withField(other, "teamId", 42L);
            TestEntities.withField(other, "rankNo", 2);

            when(userToTeamLogRepository.findLatestItem(USER_ID, TEAM_ID))
              .thenReturn(Optional.of(selected));
            when(userToTeamLogRepository.findShownItems(anyLong()))
              .thenReturn(List.of(selected, other));
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.of(slot(USER_ID)));
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private User user(Long id) {
        return User.builder().id(id).name("사용자" + id).build();
    }

    private UserEmbedding userEmbedding() {
        return userEmbedding(USER_ID);
    }

    private UserEmbedding userEmbedding(Long userId) {
        UserEmbedding embedding = new UserEmbedding();
        embedding.setUserId(userId);
        embedding.setEmbedding(new float[]{0.5f, 0.5f});
        return embedding;
    }

    private UserEmbedding userEmbeddingWithoutVector(Long userId) {
        UserEmbedding embedding = new UserEmbedding();
        embedding.setUserId(userId);
        return embedding;
    }

    /**
     * embeddingText 를 "USER-SUMMARY" 로 둔다 — {@code RecommendationSummaryFactory.userSummary} 의
     * 1층이 이 값을 그대로 돌려주므로, 요약이 어느 쪽에서 왔는지 단언으로 구분할 수 있다.
     */
    private MatchingIntentSlot slot(Long userId) {
        MatchingIntentSlot slot = new MatchingIntentSlot(user(userId));
        slot.update(null, List.of("디자이너"), List.of("Figma"), List.of("UX"),
                "포트폴리오", "온라인", "입문", "USER-SUMMARY");
        return TestEntities.withId(slot, userId * 10);
    }

    private MatchingIntentSlot slotOf(Long userId) {
        return slot(userId);
    }

    private Team team(Long id) {
        Team team = new Team();
        team.setId(id);
        team.setTitle("팀 " + id);
        team.setLeaderUserId(999L);
        team.setRole(List.of("백엔드"));
        return team;
    }

    private Team teamLedBy(Long id, Long leaderUserId) {
        Team team = team(id);
        team.setLeaderUserId(leaderUserId);
        return team;
    }

    private Team teamWithEvent(Long id, Long eventId) {
        Team team = team(id);
        team.setEventId(eventId);
        return team;
    }

    /** 요청자가 팀장인 팀 + 활동 연결. 제안 조립 테스트가 권한에 막히지 않게 한다. */
    private Team myTeamWithEvent(Long id, Long eventId) {
        Team team = teamLedBy(id, USER_ID);
        team.setEventId(eventId);
        return team;
    }

    /** embeddingText 를 "TEAM-SUMMARY" 로 둔 팀 임베딩 (요약 출처를 단언으로 가르기 위해). */
    private TeamEmbedding summarizableTeamEmbedding() {
        TeamEmbedding embedding = teamEmbedding(TEAM_ID, new float[]{0.3f, 0.4f});
        embedding.setEmbeddingText("TEAM-SUMMARY");
        return embedding;
    }

    private TeamEmbedding teamEmbedding(Long teamId, float[] vector) {
        TeamEmbedding embedding = new TeamEmbedding();
        embedding.setTeamId(teamId);
        embedding.setEmbedding(vector);
        return embedding;
    }

    private Event event(Long id) {
        Event event = new Event();
        TestEntities.withId(event, id);
        return event;
    }

    /** 분야 코드가 AI 로 나가는지 보려면 field 가 채워진 활동이 필요하다. */
    private Event eventWithField() {
        Event event = event(7L);
        event.setField(Event.Field.SCIENCE_ENGINEERING_TECH_IT);
        return event;
    }

    private TeamEmbedding teamEmbedding(Long teamId) {
        return teamEmbedding(teamId, new float[]{0.3f, 0.4f});
    }

    /**
     * 선택 컨텍스트 수집은 item → log 를 타고 shownCount 를 읽는다. 로그 헤더가 없으면
     * 그 경로에서 NPE 가 나므로 픽스처가 반드시 붙여 줘야 한다.
     */
    private UserToTeamRecommendationLog userToTeamLog() {
        UserToTeamRecommendationLog log = new UserToTeamRecommendationLog(USER_ID, null, 50, 2);
        return TestEntities.withId(log, 800L);
    }

    private TeamToUserRecommendationLog teamToUserLog() {
        TeamToUserRecommendationLog log = new TeamToUserRecommendationLog(TEAM_ID, USER_ID, 30, 1);
        return TestEntities.withId(log, 801L);
    }

    private com.example.mateon.teams.domain.TeamApplication applicationTo(Team team) {
        com.example.mateon.teams.domain.TeamApplication application =
                mock(com.example.mateon.teams.domain.TeamApplication.class);
        when(application.getTeam()).thenReturn(team);
        return application;
    }

    private com.example.mateon.teams.domain.TeamApplication applicationFrom(Long applicantId) {
        com.example.mateon.teams.domain.TeamApplication application =
                mock(com.example.mateon.teams.domain.TeamApplication.class);
        when(application.getApplicant()).thenReturn(user(applicantId));
        return application;
    }

    private TeamMember memberOf(Long userId) {
        TeamMember member = mock(TeamMember.class);
        when(member.getUser()).thenReturn(user(userId));
        return member;
    }

    private UserCollaborationScore score(Long userId, BigDecimal temperature) {
        UserCollaborationScore score = mock(UserCollaborationScore.class);
        when(score.getUserId()).thenReturn(userId);
        when(score.getTemperature()).thenReturn(temperature);
        return score;
    }

    private TeamMemberRepository.TeamMemberCount count(Long teamId, long memberCount) {
        return new TeamMemberRepository.TeamMemberCount() {
            @Override
            public Long getTeamId() {
                return teamId;
            }

            @Override
            public long getMemberCount() {
                return memberCount;
            }
        };
    }

    private Map<Long, Team> byId(Team... teams) {
        return List.of(teams).stream().collect(Collectors.toMap(Team::getId, Function.identity()));
    }

    /** 추천 아이템은 생성자가 패키지 전용이라 no-arg + 필드 주입으로 만든다. */
    private UserToTeamRecommendationItem userToTeamItem(double score, String reason) {
        UserToTeamRecommendationItem item = new UserToTeamRecommendationItem();
        TestEntities.withId(item, 500L);
        TestEntities.withField(item, "teamId", TEAM_ID);
        TestEntities.withField(item, "rankNo", 1);
        TestEntities.withField(item, "score", score);
        TestEntities.withField(item, "label", "역할이 맞습니다");
        TestEntities.withField(item, "reason", reason);
        return item;
    }

    private TeamToUserRecommendationItem teamToUserItem(double score, String reason) {
        TeamToUserRecommendationItem item = new TeamToUserRecommendationItem();
        TestEntities.withId(item, 600L);
        TestEntities.withField(item, "userId", 2L);
        TestEntities.withField(item, "rankNo", 1);
        TestEntities.withField(item, "score", score);
        TestEntities.withField(item, "label", "스킬이 맞습니다");
        TestEntities.withField(item, "reason", reason);
        return item;
    }

    /** 사용하지 않지만, 목록형 픽스처가 필요할 때 쓰기 위한 자리. */
    @SuppressWarnings("unused")
    private List<Team> teams(int count) {
        List<Team> teams = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            teams.add(team(i));
        }
        return teams;
    }
}
