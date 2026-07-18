package com.example.mateon.matching.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.client.IntentExtractResponse;
import com.example.mateon.matching.config.AiServerProperties;
import com.example.mateon.matching.domain.*;
import com.example.mateon.matching.dto.response.ExtractedDTO;
import com.example.mateon.matching.dto.snapshot.ConversationSnapshot;
import com.example.mateon.matching.dto.response.IntentSessionResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.repository.MatchingIntentMessageRepository;
import com.example.mateon.matching.repository.MatchingIntentSessionRepository;
import com.example.mateon.matching.repository.MatchingIntentSlotRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserEmbedding;
import com.example.mateon.user.repository.UserEmbeddingRepository;
import com.example.mateon.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 의도 추출 대화의 DB 작업을 담당한다. FastAPI 호출은 여기서 하지 않는다
 * (MatchingIntentService 가 TX 밖에서 호출한다 — 이유는 그쪽 주석 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MatchingIntentSessionService {

    private final MatchingIntentSessionRepository sessionRepository;
    private final MatchingIntentMessageRepository messageRepository;
    private final MatchingIntentSlotRepository slotRepository;
    private final UserRepository userRepository;
    private final UserEmbeddingRepository userEmbeddingRepository;
    private final AiServerProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * [TX1] 세션을 확보하고 사용자 발화를 저장한 뒤, FastAPI 로 보낼 대화 전체를 반환한다.
     * 반환값이 엔티티가 아닌 이유는 ConversationSnapshot 주석 참고.
     */
    public ConversationSnapshot appendUserMessage(Long userId, String message) {
        MatchingIntentSession session = resolveActiveSession(userId);

        int nextSeq = messageRepository.countBySessionId(session.getId()) + 1;
        messageRepository.save(new MatchingIntentMessage(
                session, nextSeq, IntentMessageRole.USER, message));

        List<String> userMessages = messageRepository.findMessagesBySessionIdAndRole(
                session.getId(), IntentMessageRole.USER);

        return new ConversationSnapshot(session.getId(), userMessages);
    }

    /**
     * [TX2] AI 응답을 반영한다. assistant_message 를 대화에 남기고, 완료됐으면 슬롯과
     * 임베딩을 저장한다.
     *
     * <p>슬롯과 임베딩은 같은 트랜잭션이다 — 슬롯만 있고 벡터가 없으면 추천이 조용히 0건을
     * 내고, 벡터만 있고 슬롯이 없으면 추천 근거를 설명할 수 없다.
     */
    public MatchingIntentResponseDTO applyResult(Long sessionId, Long userId, IntentExtractResponse ai) {
        MatchingIntentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

        int nextSeq = messageRepository.countBySessionId(sessionId) + 1;
        messageRepository.save(new MatchingIntentMessage(
                session, nextSeq, IntentMessageRole.ASSISTANT, ai.getAssistantMessage()));

        ExtractedDTO extracted = new ExtractedDTO(ai.getExtracted());
        boolean completed = ai.isCompleted();
        session.applyAiResult(nullToEmpty(ai.getMissingFields()), writeExtractedJson(extracted), completed);

        Long slotId = null;
        if (completed) {
            slotId = upsertSlot(userId, session, ai);
            upsertEmbedding(userId, ai.getEmbeddingVector());
        }

        return new MatchingIntentResponseDTO(sessionId, ai, slotId);
    }

    /** 진행 중인 세션과 대화를 복원한다. 없으면 empty. */
    @Transactional(readOnly = true)
    public Optional<IntentSessionResponseDTO> getCurrentSession(Long userId) {
        return sessionRepository.findByUserIdAndStatus(userId, IntentSessionStatus.IN_PROGRESS)
                .map(this::toSessionResponse);
    }

    /** 진행 중인 세션을 버린다. 새 세션은 만들지 않는다 — 다음 메시지 때 자동 생성된다. */
    public void restart(Long userId) {
        sessionRepository.findByUserIdAndStatus(userId, IntentSessionStatus.IN_PROGRESS)
                .ifPresent(MatchingIntentSession::abandon);
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    /**
     * 진행 중인 세션을 찾거나 새로 만든다.
     *
     * <p>지연 만료: 배치 잡 없이, 사용자가 메시지를 보낸 이 순간에 방치 여부를 확인한다.
     * 만료 여부에 관심 있는 건 사용자 본인의 다음 요청뿐이라 이걸로 충분하다.
     * (COMPLETED/ABANDONED 세션은 애초에 IN_PROGRESS 조회에 걸리지 않아 새 세션이 만들어진다)
     */
    private MatchingIntentSession resolveActiveSession(Long userId) {
        LocalDateTime threshold = LocalDateTime.now().minus(properties.getSessionTtl());

        Optional<MatchingIntentSession> active =
                sessionRepository.findByUserIdAndStatus(userId, IntentSessionStatus.IN_PROGRESS);

        if (active.isPresent()) {
            MatchingIntentSession session = active.get();
            if (!session.isStaleAt(threshold)) {
                return session;
            }
            log.info("방치된 의도 추출 세션 만료 처리: sessionId={}, updatedAt={}",
                    session.getId(), session.getUpdatedAt());
            session.expire();
            sessionRepository.flush();  // EXPIRED 로 바꾼 걸 먼저 반영해야 부분 유니크 인덱스와 충돌하지 않는다
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
        return sessionRepository.save(new MatchingIntentSession(user));
    }

    /** 사용자당 슬롯 1건 — 있으면 덮어쓴다. */
    private Long upsertSlot(Long userId, MatchingIntentSession session, IntentExtractResponse ai) {
        IntentExtractResponse.Extracted e = ai.getExtracted();
        if (e == null) {
            log.warn("AI 가 완료를 알렸으나 extracted 가 없음: sessionId={}", session.getId());
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }

        MatchingIntentSlot slot = slotRepository.findByUserId(userId)
                .orElseGet(() -> new MatchingIntentSlot(session.getUser()));

        slot.update(session,
                nullToEmpty(e.getDesiredRoles()), nullToEmpty(e.getSkills()), nullToEmpty(e.getInterests()),
                e.getActivityGoal(), e.getActivityStyle(), e.getExperienceLevel(),
                ai.getEmbeddingText());

        return slotRepository.save(slot).getId();
    }

    /**
     * FastAPI 가 준 벡터를 user_embeddings 에 그대로 저장한다. Spring 은 임베딩을 만들지 않는다.
     *
     * <p>차원 검증이 필수인 이유: vector(1536) 컬럼에 다른 길이를 넣으면 DB 예외가
     * GlobalExceptionHandler 의 catch-all 에 걸려 원인 불명 500 이 된다. 앞단에서 502 로 잡는다.
     */
    private void upsertEmbedding(Long userId, double[] vector) {
        if (vector == null || vector.length != properties.getEmbeddingDimension()) {
            log.warn("AI 임베딩 차원 불일치: expected={}, actual={}",
                    properties.getEmbeddingDimension(), vector == null ? null : vector.length);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }

        float[] embedding = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            embedding[i] = (float) vector[i];  // pgvector 저장 타입이 float4 → 무손실
        }

        UserEmbedding entity = userEmbeddingRepository.findById(userId)
                .orElseGet(() -> {
                    UserEmbedding created = new UserEmbedding();
                    created.setUserId(userId);
                    return created;
                });
        entity.setEmbedding(embedding);
        userEmbeddingRepository.save(entity);
    }

    private IntentSessionResponseDTO toSessionResponse(MatchingIntentSession session) {
        List<IntentSessionResponseDTO.MessageDTO> messages =
                messageRepository.findBySessionIdOrderBySeqAsc(session.getId()).stream()
                        .map(IntentSessionResponseDTO.MessageDTO::new)
                        .toList();

        List<String> missingFields = nullToEmpty(session.getLastMissingFields());

        return new IntentSessionResponseDTO(
                session.getId(),
                session.getStatus(),
                missingFields.isEmpty() && session.getLastExtractedJson() != null,
                missingFields,
                readExtractedJson(session.getLastExtractedJson()),
                messages
        );
    }

    private String writeExtractedJson(ExtractedDTO extracted) {
        try {
            return objectMapper.writeValueAsString(extracted);
        } catch (Exception e) {
            // 저장 실패가 대화 흐름을 막을 이유는 없다 — 복원 시 extracted 만 비게 된다.
            log.warn("extracted 직렬화 실패", e);
            return null;
        }
    }

    private ExtractedDTO readExtractedJson(String json) {
        if (json == null) {
            return new ExtractedDTO();
        }
        try {
            return objectMapper.readValue(json, ExtractedDTO.class);
        } catch (Exception e) {
            log.warn("last_extracted_json 역직렬화 실패: {}", json, e);
            return new ExtractedDTO();
        }
    }

    private static List<String> nullToEmpty(List<String> value) {
        return value != null ? value : Collections.emptyList();
    }
}
