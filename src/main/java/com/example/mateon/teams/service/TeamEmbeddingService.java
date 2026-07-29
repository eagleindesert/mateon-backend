package com.example.mateon.teams.service;

import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.matching.config.AiServerProperties;
import com.example.mateon.teams.client.TeamEmbeddingClient;
import com.example.mateon.teams.client.TeamEmbeddingRefreshRequest;
import com.example.mateon.teams.client.TeamEmbeddingRefreshResponse;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import com.example.mateon.teams.domain.TeamEmbeddingRefreshStatus;
import com.example.mateon.teams.repository.TeamEmbeddingRepository;
import com.example.mateon.teams.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 팀 임베딩 갱신 오케스트레이터. 팀 생성/수정 커밋 후 비동기 리스너에서 호출된다.
 *
 * <p>클래스에 @Transactional 을 걸지 않는 이유: AI 호출이 read-timeout(60초)까지 걸릴 수 있어
 * 그동안 DB 커넥션을 점유하면 안 된다 (MatchingIntentService 와 같은 원칙). 조회와 upsert 는
 * 각각 리포지토리 자체의 짧은 트랜잭션으로 충분하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamEmbeddingService {

    /** last_error 저장 상한. 진단 단서만 남기면 되므로 길게 둘 이유가 없다. */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 버전 충돌 시 재판정 포함 최대 저장 시도 횟수 (saveIfFresh 주석 참고). */
    private static final int MAX_SAVE_ATTEMPTS = 2;

    private final TeamRepository teamRepository;
    private final EventRepository eventRepository;
    private final TeamEmbeddingRepository teamEmbeddingRepository;
    private final TeamEmbeddingClient client;
    private final AiServerProperties properties;

    /**
     * 팀 정보를 fresh 조회해 AI 서버로 임베딩을 계산하고 team_embeddings 에 upsert 한다.
     * 이벤트에는 teamId 만 담겨 오므로 연속 수정 시에도 항상 최신 데이터로 계산된다.
     */
    public void refresh(Long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            // 커밋 직후 팀이 삭제된 레이스 — 임베딩을 만들 이유가 없다.
            // FK 가 ON DELETE CASCADE 라 실패 상태를 기록할 행도 만들 수 없다 (V8 주석 참고).
            log.warn("팀 임베딩 갱신 스킵: 팀이 존재하지 않음 (teamId={})", teamId);
            return;
        }

        // AI 호출 "전에" 찍는다 — 이 결과가 팀의 어느 시점을 반영하는지의 기준이다.
        // 호출 뒤에 읽으면 그 사이 들어온 수정을 자기 것으로 착각해 낡은 결과가 최신 행세를 한다.
        LocalDateTime sourceUpdatedAt = team.getUpdatedAt();

        try {
            // contest_field: Event.category 는 CONTEST/EXTERNAL/SCHOOL enum 이라 분야 정보가 아니다.
            // 분야가 담기는 것은 제목("2026 커머스 아이디어 공모전" 등)이므로 title 을 보낸다.
            Event event = team.getEventId() != null
                    ? eventRepository.findById(team.getEventId()).orElse(null)
                    : null;

            TeamEmbeddingRefreshRequest request = new TeamEmbeddingRefreshRequest(
                    buildIntroText(team),
                    team.getRole(),
                    team.getRequiredSkills(),
                    event != null ? event.getTitle() : null
            );

            TeamEmbeddingRefreshResponse ai = client.refresh(request); // TX 밖 — 최대 60초

            upsert(teamId, ai, sourceUpdatedAt);
        } catch (Exception e) {
            // 실패를 행에 남긴 뒤 그대로 재던진다 — 호출부(TeamEmbeddingRefreshListener)의
            // warn 로깅 동작을 바꾸지 않기 위함이다.
            recordFailure(teamId, sourceUpdatedAt, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * AI 스펙상 활동 목표/스타일/강도/초보 환영/현재 팀 구성은 별도 필드 없이 intro_text 의
     * 자유 서술에서 추출된다. 팀의 서술형 필드를 전부 자연어 한 덩어리로 이어 붙인다.
     */
    private String buildIntroText(Team team) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "제목", team.getTitle());
        appendLine(sb, "소개", team.getPromotionText());
        appendLine(sb, "팀 특성", team.getCharacteristic());
        if (team.getCapacity() != null) {
            appendLine(sb, "모집 정원", team.getCapacity() + "명");
        }
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(label).append(": ").append(value.trim());
    }

    /**
     * 차원 검증이 필수인 이유: vector(1536) 컬럼에 다른 길이를 넣으면 DB 예외로 원인 불명이
     * 된다. 비동기 경로라 예외 대신 warn 후 중단한다 — 기존 임베딩(있다면)이 유지된다.
     *
     * @param sourceUpdatedAt 이 결과가 반영하는 팀 데이터의 시점. 행에 함께 기록해 다음 결과의
     *                        낡음 판정 기준이 된다.
     */
    private void upsert(Long teamId, TeamEmbeddingRefreshResponse ai, LocalDateTime sourceUpdatedAt) {
        double[] vector = ai.getEmbeddingVector();
        if (vector == null || vector.length != properties.getEmbeddingDimension()) {
            log.warn("팀 임베딩 차원 불일치로 저장 스킵: teamId={}, expected={}, actual={}",
                    teamId, properties.getEmbeddingDimension(),
                    vector == null ? null : vector.length);
            recordFailure(teamId, sourceUpdatedAt, "차원 불일치: expected=" + properties.getEmbeddingDimension()
                    + ", actual=" + (vector == null ? null : vector.length));
            return;
        }

        float[] embedding = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            embedding[i] = (float) vector[i];  // pgvector 저장 타입이 float4 → 무손실
        }

        boolean saved = saveIfFresh(teamId, sourceUpdatedAt, entity -> {
            entity.setEmbedding(embedding);
            entity.setEmbeddingText(ai.getEmbeddingText());
            entity.setMissingFields(ai.getMissingFields());

            TeamEmbeddingRefreshResponse.Metadata metadata = ai.getMetadata();
            if (metadata != null) {
                entity.setRecruitingRoles(metadata.getRecruitingRoles());
                entity.setRequiredSkills(metadata.getRequiredSkills());
                entity.setActivityGoal(metadata.getActivityGoal());
                entity.setActivityStyle(metadata.getActivityStyle());
                entity.setActivityIntensity(metadata.getActivityIntensity());
                entity.setBeginnerFriendly(metadata.getBeginnerFriendly());
            }

            entity.setRefreshStatus(TeamEmbeddingRefreshStatus.SUCCESS);
            entity.setLastAttemptedAt(LocalDateTime.now());
            entity.setConsecutiveFailures(0);
            entity.setLastError(null);
            // 내용과 함께 갱신해야 한다 — 이 행이 이제 sourceUpdatedAt 시점을 반영한다는 선언이다.
            entity.setSourceUpdatedAt(sourceUpdatedAt);
        });

        if (saved) {
            List<String> missing = ai.getMissingFields();
            log.info("팀 임베딩 저장 완료: teamId={}, missingFields={}",
                    teamId, missing == null ? List.of() : missing);
        }
    }

    /**
     * 갱신 실패를 행에 남긴다. 행이 없으면 벡터 없이(embedding=null) 만든다 — 첫 갱신부터 실패한
     * 팀은 이 경로로만 존재가 드러난다.
     *
     * <p>기존 임베딩은 건드리지 않는다. 낡은 값이라도 남겨 두는 편이 추천에서 통째로 사라지는
     * 것보다 낫다는 기존 판단(upsert 주석)을 유지한다. 같은 이유로 source_updated_at 도 올리지
     * 않는다 — 내용은 여전히 예전 시점 것이므로, 실패한 시도의 기준 시각을 적으면 이후 도착하는
     * 정상 결과가 낡은 것으로 오판된다.
     *
     * <p>기록 자체의 실패를 삼키는 이유: 팀 삭제 레이스면 여기서 FK 위반이 나는데, 그 예외가
     * 위로 올라가면 호출부에서 원래 실패 원인이 가려진다.
     */
    private void recordFailure(Long teamId, LocalDateTime sourceUpdatedAt, String reason) {
        try {
            saveIfFresh(teamId, sourceUpdatedAt, entity -> {
                entity.setRefreshStatus(TeamEmbeddingRefreshStatus.FAILED);
                entity.setLastAttemptedAt(LocalDateTime.now());
                entity.setConsecutiveFailures(entity.getConsecutiveFailures() + 1);
                entity.setLastError(truncate(reason));
            });
        } catch (Exception e) {
            log.warn("팀 임베딩 갱신 실패 상태 기록 실패 (원래 실패 원인은 호출부 로그 참고). teamId={}",
                    teamId, e);
        }
    }

    /**
     * 낡은 결과를 걸러 내고 저장한다 (V26).
     *
     * <p>막으려는 것: 생성 갱신과 수정 갱신이 동시에 돌 때, AI 응답이 늦게 온 쪽이 무조건 이기는
     * last-write-wins. 실제로 짧은 수정 텍스트가 먼저 끝나고 긴 생성 텍스트가 0.3초 뒤 도착해
     * 최신 임베딩을 덮은 사례가 있다. 순서를 보장하는 대신, 도착한 결과가 이미 저장된 것보다
     * 낡았으면 버린다.
     *
     * <p>재시도: 판정과 저장 사이에 상대가 끼어들면 버전 충돌(기존 행)이나 PK 중복(첫 갱신이라
     * 양쪽 다 insert 를 시도한 경우)이 난다. 어느 쪽이든 상대가 방금 쓴 행을 다시 읽어 재판정하며,
     * 대부분 그 자리에서 폐기로 끝난다. 한 번만 재시도하는 이유는 경합 상대가 (생성, 수정) 두
     * 갱신뿐이라 연쇄 충돌이 없기 때문이다. 저장 실패 후 다시 읽어도 되는 이유는 이 서비스에
     * 트랜잭션이 없어 조회/저장이 각각 짧은 트랜잭션으로 끝나기 때문이다 (클래스 주석 참고).
     *
     * <p>mutator 는 재시도 시 다시 읽은 엔티티에 대해 재실행되므로, 엔티티 현재 값에서 파생되는
     * 변경(연속 실패 횟수 등)도 그대로 맞는다.
     *
     * @return 저장했으면 true, 낡은 결과라 버렸으면 false
     */
    private boolean saveIfFresh(Long teamId, LocalDateTime sourceUpdatedAt, Consumer<TeamEmbedding> mutator) {
        for (int attempt = 1; ; attempt++) {
            TeamEmbedding entity = loadOrCreate(teamId);

            LocalDateTime stored = entity.getSourceUpdatedAt();
            // stored 가 null 이면 판정 불가(V26 이전 행 또는 첫 갱신) — 버리지 않는다.
            // 같은 시각이면 통과시킨다: 재시도나 멱등 재계산이 자기 자신 때문에 막히면 안 된다.
            if (stored != null && sourceUpdatedAt != null && sourceUpdatedAt.isBefore(stored)) {
                log.info("낡은 팀 임베딩 결과 폐기: teamId={}, 결과 기준={}, 행 기준={}",
                        teamId, sourceUpdatedAt, stored);
                return false;
            }

            mutator.accept(entity);

            try {
                teamEmbeddingRepository.save(entity);
                return true;
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                // DataIntegrityViolationException 에는 "팀이 삭제된 뒤 도착한 insert"의 FK 위반도
                // 섞여 든다. 그건 재시도해도 같은 실패라 한 번 더 시도하고 그대로 던진다 (V8 주석).
                if (attempt >= MAX_SAVE_ATTEMPTS) {
                    throw e;
                }
                log.debug("팀 임베딩 저장 충돌 — 다시 읽어 재판정: teamId={}", teamId);
            }
        }
    }

    private TeamEmbedding loadOrCreate(Long teamId) {
        return teamEmbeddingRepository.findById(teamId)
                .orElseGet(() -> {
                    TeamEmbedding created = new TeamEmbedding();
                    created.setTeamId(teamId);
                    return created;
                });
    }

    /** last_error 는 진단용 단서일 뿐이라 스택 전체를 담지 않는다. */
    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
    }
}
