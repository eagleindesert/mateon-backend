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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

            upsert(teamId, ai);
        } catch (Exception e) {
            // 실패를 행에 남긴 뒤 그대로 재던진다 — 호출부(TeamEmbeddingRefreshListener)의
            // warn 로깅 동작을 바꾸지 않기 위함이다.
            recordFailure(teamId, e.getClass().getSimpleName() + ": " + e.getMessage());
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
     */
    private void upsert(Long teamId, TeamEmbeddingRefreshResponse ai) {
        double[] vector = ai.getEmbeddingVector();
        if (vector == null || vector.length != properties.getEmbeddingDimension()) {
            log.warn("팀 임베딩 차원 불일치로 저장 스킵: teamId={}, expected={}, actual={}",
                    teamId, properties.getEmbeddingDimension(),
                    vector == null ? null : vector.length);
            recordFailure(teamId, "차원 불일치: expected=" + properties.getEmbeddingDimension()
                    + ", actual=" + (vector == null ? null : vector.length));
            return;
        }

        float[] embedding = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            embedding[i] = (float) vector[i];  // pgvector 저장 타입이 float4 → 무손실
        }

        TeamEmbedding entity = loadOrCreate(teamId);
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

        teamEmbeddingRepository.save(entity);
        List<String> missing = ai.getMissingFields();
        log.info("팀 임베딩 저장 완료: teamId={}, missingFields={}",
                teamId, missing == null ? List.of() : missing);
    }

    /**
     * 갱신 실패를 행에 남긴다. 행이 없으면 벡터 없이(embedding=null) 만든다 — 첫 갱신부터 실패한
     * 팀은 이 경로로만 존재가 드러난다.
     *
     * <p>기존 임베딩은 건드리지 않는다. 낡은 값이라도 남겨 두는 편이 추천에서 통째로 사라지는
     * 것보다 낫다는 기존 판단(upsert 주석)을 유지한다.
     *
     * <p>기록 자체의 실패를 삼키는 이유: 팀 삭제 레이스면 여기서 FK 위반이 나는데, 그 예외가
     * 위로 올라가면 호출부에서 원래 실패 원인이 가려진다.
     */
    private void recordFailure(Long teamId, String reason) {
        try {
            TeamEmbedding entity = loadOrCreate(teamId);
            entity.setRefreshStatus(TeamEmbeddingRefreshStatus.FAILED);
            entity.setLastAttemptedAt(LocalDateTime.now());
            entity.setConsecutiveFailures(entity.getConsecutiveFailures() + 1);
            entity.setLastError(truncate(reason));
            teamEmbeddingRepository.save(entity);
        } catch (Exception e) {
            log.warn("팀 임베딩 갱신 실패 상태 기록 실패 (원래 실패 원인은 호출부 로그 참고). teamId={}",
                    teamId, e);
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
