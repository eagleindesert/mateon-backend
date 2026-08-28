package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.repository.AiChatSessionRepository;
import com.example.mateon.aichat.repository.AiDomainTaskRepository;
import com.example.mateon.matching.client.recommendation.RecommendationClient;
import com.example.mateon.matching.domain.MatchingIntentSession;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.domain.UserToTeamRecommendationItem;
import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import com.example.mateon.matching.dto.response.TeamRecommendationResponseDTO;
import com.example.mateon.matching.repository.MatchingIntentSessionRepository;
import com.example.mateon.matching.repository.MatchingIntentSlotRepository;
import com.example.mateon.matching.repository.UserToTeamRecommendationLogRepository;
import com.example.mateon.support.AiStubSupport;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import com.example.mateon.teams.domain.TeamEmbeddingRefreshStatus;
import com.example.mateon.teams.repository.TeamEmbeddingRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserEmbedding;
import com.example.mateon.user.repository.UserEmbeddingRepository;
import com.example.mateon.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 가 준 {@code component_scores} 가 원문 그대로 DB 까지 가는지 확인한다.
 *
 * <p>
 * {@code UserToTeamRecommendationItem.componentScores} 주석은 이 값이 <b>"원문 JSON 문자열
 * 그대로"</b>여야 한다고 규약을 못박는다. 선택 피드백으로 AI 에 되돌려 줄 값이라 한 글자도
 * 바뀌면 안 되기 때문이다. 그런데 그 규약을 지키는지 보는 테스트가 없었다 —
 * {@code RecommendationLogService} 는 {@code JsonNode.toString()} 하나로 그 일을 하는데,
 * 그게 실제 AI 응답에 대고 도는 자리가 어디에도 없었다.
 *
 * <p>
 * DTO 까지만 보는 검증({@code RecommendationLiveTest})으로는 부족하다. 예를 들어
 * {@code toString()} 을 {@code asText()} 로 바꾸면 객체 노드에서 빈 문자열이 나오는데,
 * DTO 는 멀쩡하고 DB 만 비어서 <b>여기서만</b> 잡힌다.
 *
 * <p>
 * 문자열 동등이 아니라 <b>파싱 후 대조</b>를 한다. 키 순서나 공백까지 묶으면 스텁 payload 를
 * 손볼 때마다 깨져서, 계약과 무관한 이유로 이 테스트가 먼저 꺼진다.
 *
 * <p>
 * <b>이 테스트는 조용히 통과하기 쉬운 구조다.</b> {@code RecommendationService} 는 후보가
 * 비면 AI 를 부르지 않고 빈 배열을 돌려주고, 기록 실패는 try/catch 로 삼킨다. 즉 픽스처를
 * 잘못 심어도 저장이 통째로 실패해도 호출은 성공으로 끝난다. 그래서 DB 를 보기 전에
 * <b>추천 결과가 비어 있지 않은지</b>를 먼저 단정하고, 성공 판정은 반환값이 아니라
 * <b>저장된 행</b>으로만 한다.
 */
class RecommendationPersistenceLiveTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 스텁이 역할 겹침으로 점수를 가른다. 겹치는 팀과 안 겹치는 팀을 하나씩 둔다. */
    private static final String MATCHING_ROLE = "디자이너";
    private static final String OTHER_ROLE = "백엔드";

    @Autowired
    RecommendationQueryService queryService;
    @Autowired
    RecommendationLogService logService;

    @Autowired
    UserRepository userRepository;
    @Autowired
    UserEmbeddingRepository userEmbeddingRepository;
    @Autowired
    AiChatSessionRepository chatSessionRepository;
    @Autowired
    AiDomainTaskRepository taskRepository;
    @Autowired
    MatchingIntentSessionRepository sessionRepository;
    @Autowired
    MatchingIntentSlotRepository slotRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    TeamEmbeddingRepository teamEmbeddingRepository;
    @Autowired
    UserToTeamRecommendationLogRepository logRepository;
    @Autowired
    EntityManager entityManager;

    private RecommendationService service;
    private Long userId;
    private Long matchingTeamId;
    private Long otherTeamId;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        // 질의/저장 서비스는 AI 의존이 없는 순수 빈이라 그대로 오토와이어한다.
        // (RecommendationQueryService 는 리포지토리를 11개 받는다 — 손으로 조립할 이유가 없다)
        // AI 를 쥔 최상위 서비스만 스텁을 향한 클라이언트로 직접 만든다.
        service = new RecommendationService(queryService, logService,
          new RecommendationClient(AiStubSupport.aiCallTemplate()));

        userId = newUserWithIntent();
        matchingTeamId = newTeamWithEmbedding("디자인 팀", MATCHING_ROLE);
        otherTeamId = newTeamWithEmbedding("백엔드 팀", OTHER_ROLE);
    }

    @Test
    @DisplayName("추천 결과가 로그와 아이템으로 저장된다")
    void recommendationsArePersisted() {
        List<TeamRecommendationResponseDTO> recommended = recommend();

        assertThat(recommended)
          .as("추천이 비었다 — 후보 픽스처가 안 심겼거나 AI 가 호출되지 않았다")
          .isNotEmpty();

        UserToTeamRecommendationLog log = reloadLog();
        assertThat(log.getUserId()).isEqualTo(userId);
        assertThat(log.getItems())
          .extracting(UserToTeamRecommendationItem::getTeamId)
          .containsExactlyInAnyOrder(matchingTeamId, otherTeamId);
    }

    /**
     * 이 클래스의 존재 이유다. 저장된 문자열을 다시 파싱해 AI 가 준 키·값과 대조한다.
     * 스텁의 component_scores 는 6키 고정이라(New-ComponentScores) 그 집합을 그대로 확인한다.
     */
    @Test
    @DisplayName("component_scores 가 원문 JSON 그대로 저장된다 (문자열로 뭉개지지 않는다)")
    void componentScoresArePersistedAsRawJson() {
        recommend();

        for (UserToTeamRecommendationItem item : reloadLog().getItems()) {
            String stored = item.getComponentScores();

            assertThat(stored)
              .as("component_scores 가 비어 있다 — toString() 경로가 깨졌다")
              .isNotBlank();

            JsonNode parsed = JSON.readTree(stored);
            assertThat(parsed.isObject())
              .as("저장된 값이 JSON 객체가 아니다: %s", stored)
              .isTrue();
            assertThat(parsed.propertyNames()).contains(
              "similarity", "role_match", "deficit_fit",
              "activity_style_match", "beginner_fit", "activity_time_match");
        }
    }

    /**
     * 스텁은 <b>일부러 점수 오름차순으로</b> 돌려준다. 백엔드가 내림차순으로 다시 세우지
     * 않으면 화면에도 기록에도 가장 안 맞는 팀이 1위로 남는다. 그 정렬이 응답에만 적용되고
     * 저장에는 빠지는 경우가 있어, 응답이 아니라 저장된 rank_no 로 확인한다.
     */
    @Test
    @DisplayName("rank_no 는 점수 내림차순이다 (AI 가 준 순서를 그대로 쓰지 않는다)")
    void rankIsStoredInDescendingScoreOrder() {
        recommend();

        List<UserToTeamRecommendationItem> items = reloadLog().getItems().stream()
          .sorted(Comparator.comparingInt(UserToTeamRecommendationItem::getRankNo))
          .toList();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getScore()).isGreaterThan(items.get(1).getScore());
        assertThat(items.get(0).getTeamId())
          .as("역할이 겹치는 팀이 1위여야 한다")
          .isEqualTo(matchingTeamId);
    }

    @Test
    @DisplayName("label 과 score 가 응답 그대로 저장된다")
    void labelAndScoreArePersisted() {
        List<TeamRecommendationResponseDTO> recommended = recommend();

        assertThat(recommended).isNotEmpty();
        assertThat(reloadLog().getItems()).allSatisfy(item -> {
            assertThat(item.getLabel()).isNotBlank();
            assertThat(item.getScore()).isNotNaN();
        });
    }

    // --- 하네스 -------------------------------------------------------------

    private List<TeamRecommendationResponseDTO> recommend() {
        return service.recommendTeams(userId, null, 10);
    }

    /**
     * flush 로 실제 SQL 을 내보내고 clear 로 1차 캐시를 비운다. 저장은 별도 빈
     * ({@code RecommendationLogService}) 이 하지만 테스트 트랜잭션에 합류하므로, 이걸 안 하면
     * 방금 만든 객체를 그대로 돌려받아 DB 왕복을 확인하지 못한다.
     */
    private UserToTeamRecommendationLog reloadLog() {
        entityManager.flush();
        entityManager.clear();

        List<UserToTeamRecommendationLog> logs = logRepository.findAll();
        assertThat(logs)
          .as("추천 로그가 저장되지 않았다 — RecommendationService 가 기록 실패를 삼켰다")
          .hasSize(1);
        return logs.get(0);
    }

    // --- 픽스처 -------------------------------------------------------------

    /**
     * 추천 질의에는 user_embeddings 와 matching_intent_slots 가 둘 다 필요하다
     * ({@code RecommendationQueryService.gather}). 슬롯의 session_id 가 NOT NULL 이라
     * 채팅 세션 → 도메인 태스크 → 의도 세션까지 실제 FK 사슬을 그대로 심는다.
     */
    private Long newUserWithIntent() {
        User user = userRepository.save(User.builder()
          .email(UUID.randomUUID() + "@test.ac.kr")
          .name("추천 저장 테스트 유저")
          .build());

        AiChatSession chatSession = chatSessionRepository.save(new AiChatSession(user));
        AiDomainTask task = taskRepository.save(
          new AiDomainTask(chatSession, user, RoutableDomain.MATCHING_INTENT));
        MatchingIntentSession session = sessionRepository.save(
          new MatchingIntentSession(user, task));

        MatchingIntentSlot slot = new MatchingIntentSlot(user);
        slot.update(session,
          List.of(MATCHING_ROLE), List.of("Figma"), List.of("디자인"),
          "포트폴리오", "온라인", "입문", "임베딩 원문");
        slotRepository.save(slot);

        UserEmbedding embedding = new UserEmbedding();
        embedding.setUserId(user.getId());
        embedding.setEmbedding(vector());
        userEmbeddingRepository.save(embedding);

        return user.getId();
    }

    /**
     * AI 요청의 후보 메타데이터는 teams 원본이 아니라 team_embeddings 의 정규화 값을 쓴다
     * ({@code RecommendationService.buildRequest}). 그래서 스텁의 점수 분기를 가르는 건
     * 여기 넣는 {@code recruitingRoles} 다.
     */
    private Long newTeamWithEmbedding(String title, String recruitingRole) {
        Team team = new Team();
        team.setTitle(title);
        team.setPromotionText("함께할 팀원을 찾습니다.");
        team.setCapacity(4);
        team.setRole(List.of(recruitingRole));
        team.setRequiredSkills(List.of("Figma"));
        team.setIsRecruiting(true);
        Long teamId = teamRepository.save(team).getId();

        TeamEmbedding embedding = new TeamEmbedding();
        embedding.setTeamId(teamId);
        embedding.setEmbedding(vector());
        embedding.setEmbeddingText("제목: " + title);
        embedding.setRecruitingRoles(List.of(recruitingRole));
        embedding.setRequiredSkills(List.of("Figma"));
        embedding.setActivityStyle("온라인");
        embedding.setBeginnerFriendly(true);
        embedding.setRefreshStatus(TeamEmbeddingRefreshStatus.SUCCESS);
        embedding.setLastAttemptedAt(LocalDateTime.now());
        teamEmbeddingRepository.save(embedding);

        return teamId;
    }

    /**
     * 값 자체는 의미가 없다. 스텁은 벡터를 계산에 쓰지 않고 차원만 확인하며, 여기서 볼 것은
     * 저장 경로지 유사도가 아니다.
     */
    private static float[] vector() {
        float[] values = new float[1536];
        for (int i = 0; i < values.length; i++) {
            values[i] = 0.001f * i;
        }
        return values;
    }
}
