package com.example.mateon.teams.service;

import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.support.AiStubSupport;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.teams.client.TeamEmbeddingClient;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import com.example.mateon.teams.domain.TeamEmbeddingRefreshStatus;
import com.example.mateon.teams.repository.TeamEmbeddingRepository;
import com.example.mateon.teams.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 가 준 임베딩이 pgvector 컬럼까지 온전히 가는지 확인한다.
 *
 * <p>
 * 이 구간은 반쪽짜리 검증으로는 못 본다. 목 클라이언트를 쓰면 우리가 만든 {@code double[]}
 * 를 우리가 다시 확인하는 꼴이고, 목 리포지토리를 쓰면 {@code vector(1536)} 컬럼이 실행되지
 * 않는다. 둘 다 진짜여야 <b>AI 응답 → double[] → float[] → pgvector</b> 전 구간이 돈다.
 *
 * <p>
 * 조립 방식은 {@code PortfolioSummaryServiceIntegrationTest} 와 같다 — 리포지토리는
 * 오토와이어하고 서비스는 손으로 만든다. 다른 건 하나뿐인데, 목 클라이언트 자리에
 * <b>스텁을 향한 진짜 클라이언트</b>가 들어간다는 점이다.
 *
 * <p>
 * 저장 뒤에 {@code flush()} + {@code clear()} 를 하고 다시 읽는다. 안 그러면 영속성
 * 컨텍스트에 남아 있는 <b>방금 그 객체</b>를 돌려받아, DB 가 이 벡터를 받아들였는지도
 * 되읽을 때 제대로 복원되는지도 확인하지 못한다.
 *
 * <p>
 * 운영 경로인 {@code TeamEmbeddingRefreshListener} 는 {@code @Async} 라 여기서 쓰지 않는다.
 * 스레드가 갈리면 테스트 트랜잭션과 분리되고, 여기서 볼 것은 비동기 배선이 아니라 저장 결과다.
 */
class TeamEmbeddingPersistenceLiveTest extends IntegrationTestBase {

    @Autowired
    TeamRepository teamRepository;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    TeamEmbeddingRepository teamEmbeddingRepository;
    @Autowired
    EntityManager entityManager;

    private TeamEmbeddingService service;

    /**
     * 스텁이 없으면 여기서 건너뛴다. {@link IntegrationTestBase} 의 static 블록이 먼저 돌아
     * Postgres 컨테이너는 이미 떠 있는데, 그건 JVM 전체가 공유하는 것이라 이 클래스 때문에
     * 추가로 드는 비용은 아니다.
     */
    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        service = new TeamEmbeddingService(
          teamRepository,
          eventRepository,
          teamEmbeddingRepository,
          new TeamEmbeddingClient(AiStubSupport.aiRestTemplate(), AiStubSupport.properties()),
          AiStubSupport.properties());
    }

    @Test
    @DisplayName("AI 임베딩이 vector(1536) 컬럼에 저장되고 그대로 되읽힌다")
    void embeddingSurvivesRoundTrip() {
        Long teamId = newTeam();

        service.refresh(teamId);

        TeamEmbedding saved = reload(teamId);
        assertThat(saved.getEmbedding()).hasSize(1536);
    }

    /**
     * {@code float} 로 좁히는 변환이 값을 망가뜨리지 않는지 본다. NaN/Infinity 가 섞이면
     * pgvector 가 거부하는데, 그 실패는 비동기 경로에서 warn 로그로만 남아 눈에 띄지 않는다.
     */
    @Test
    @DisplayName("저장된 벡터 값이 전부 유한하다 (double→float 변환이 값을 깨지 않는다)")
    void storedVectorValuesAreFinite() {
        Long teamId = newTeam();

        service.refresh(teamId);

        for (float value : reload(teamId).getEmbedding()) {
            assertThat(Float.isFinite(value))
              .as("저장된 벡터에 유한하지 않은 값이 있다: %s", value)
              .isTrue();
        }
    }

    /**
     * 임베딩만 보고 끝내면 안 된다. metadata 는 중첩 객체라 바깥 필드와 따로 깨지는데,
     * 여기까지 와야 "AI 가 준 값이 컬럼에 들어갔는가"가 확인된다.
     */
    @Test
    @DisplayName("응답 metadata 가 컬럼으로 옮겨진다")
    void metadataIsPersisted() {
        Long teamId = newTeam();

        service.refresh(teamId);

        TeamEmbedding saved = reload(teamId);
        assertThat(saved.getEmbeddingText()).isNotBlank();
        assertThat(saved.getRecruitingRoles()).isNotNull();
        assertThat(saved.getRequiredSkills()).isNotNull();
    }

    /**
     * 스텁은 {@code missing_fields} 를 항상 한 건 남긴다 — 미추출 항목이 있어도 벡터는 함께
     * 온다는 명세를 재현한 것이다. 그걸 실패로 읽어 상태를 실패로 남기면, 이후 갱신 판정이
     * 전부 어긋난다.
     */
    @Test
    @DisplayName("missing_fields 가 남아 있어도 상태는 SUCCESS 다")
    void missingFieldsDoNotMarkFailure() {
        Long teamId = newTeam();

        service.refresh(teamId);

        TeamEmbedding saved = reload(teamId);
        assertThat(saved.getMissingFields()).isNotEmpty();
        assertThat(saved.getRefreshStatus()).isEqualTo(TeamEmbeddingRefreshStatus.SUCCESS);
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getLastError()).isNull();
    }

    // --- 하네스 -------------------------------------------------------------

    /**
     * flush 로 실제 SQL 을 내보내고 clear 로 1차 캐시를 비운다. 둘 다 해야 다음 조회가
     * DB 를 실제로 읽는다 — 그래야 pgvector 가 이 벡터를 받아들였는지 알 수 있다.
     */
    private TeamEmbedding reload(Long teamId) {
        entityManager.flush();
        entityManager.clear();
        return teamEmbeddingRepository.findById(teamId).orElseThrow();
    }

    private Long newTeam() {
        Team team = new Team();
        team.setTitle("임베딩테스트 팀");
        team.setPromotionText("디자인 협업을 함께할 팀원을 찾습니다. 온라인 위주로 주 2회 모입니다.");
        team.setCharacteristic("초보 환영");
        team.setCapacity(4);
        team.setRole(List.of("디자이너", "기획자"));
        team.setRequiredSkills(List.of("Figma"));
        return teamRepository.save(team).getId();
    }
}
