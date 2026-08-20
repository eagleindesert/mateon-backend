package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.domain.TaskCloseReason;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.service.AiChatService;
import com.example.mateon.aichat.service.AiDomainTaskService;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.client.intent.IntentExtractResponse;
import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.matching.domain.*;
import com.example.mateon.matching.dto.response.ExtractedDTO;
import com.example.mateon.matching.dto.snapshot.ConversationSnapshot;
import com.example.mateon.matching.dto.response.IntentSessionResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
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

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 의도 추출 대화의 DB 작업을 담당한다. FastAPI 호출은 여기서 하지 않는다
 * (MatchingIntentService 가 TX 밖에서 호출한다 — 이유는 그쪽 주석 참고).
 *
 * <p>
 * 대화 이력은 이 도메인이 직접 갖지 않고 AI 채팅 통합 로그(AiChatService)에 맡긴다.
 * 게이트웨이가 위임할 때 발화를 이미 기록해 두므로, 여기서 또 쓰면 두 벌이 된다.
 *
 * <p>
 * 세션의 <b>수명</b>도 이 클래스가 직접 판단하지 않는다. 열고·이어가고·만료시키는 건
 * {@link AiDomainTaskService} 의 일이고 여기서는 그 결과를 받아 쓴다 — 그래야 도메인이 늘어도
 * 만료 규칙이 복제되지 않고, 게이트웨이가 도메인을 모른 채 라우팅을 판단할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MatchingIntentSessionService {

    private final MatchingIntentSessionRepository sessionRepository;
    private final AiChatService chatService;
    private final AiDomainTaskService taskService;
    private final MatchingIntentSlotRepository slotRepository;
    private final UserRepository userRepository;
    private final UserEmbeddingRepository userEmbeddingRepository;
    private final AiServerProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * [TX1] 작업을 확보하고, 통합 로그에 이미 적힌 사용자 발화를 이 작업 소관으로 표시한 뒤,
     * FastAPI 로 보낼 대화 전체를 반환한다.
     *
     * <p>
     * 발화를 여기서 저장하지 않는 게 핵심이다. 게이트웨이가 라우팅을 판단하려면 위임 전에
     * 이미 발화를 기록해 둬야 하고, 여기서 또 쓰면 같은 문장이 두 벌 남는다. 그래서 저장은
     * 통합 로그 한 곳에서 하고 여기서는 "이 턴은 내 소관"이라고 도장만 찍는다.
     *
     * <p>
     * 도장을 찍고 나서 읽어야 방금 발화까지 배열에 포함된다 (같은 트랜잭션이라 JPQL 조회
     * 직전에 자동 flush 된다).
     *
     * <p>
     * 반환값이 엔티티가 아닌 이유는 ConversationSnapshot 주석 참고.
     */
    public ConversationSnapshot bindTurn(Long userId, AiChatTurn turn) {
        AiDomainTask task = taskService.openOrResume(
          turn.chatSessionId(), userId, RoutableDomain.MATCHING_INTENT);

        MatchingIntentSession session = sessionRepository.findByTaskId(task.getId())
          .orElseGet(() -> sessionRepository.save(
          new MatchingIntentSession(requireUser(userId), task)));

        chatService.assignTask(turn.messageId(), task.getId());

        List<String> userMessages = chatService.findUserContents(task.getId());

        return new ConversationSnapshot(session.getId(), userMessages);
    }

    /**
     * [TX2] AI 응답을 반영한다. assistant_message 를 대화에 남기고, 완료됐으면 작업을 닫은 뒤
     * 슬롯과 임베딩을 저장한다.
     *
     * <p>
     * 슬롯과 임베딩은 같은 트랜잭션이다 — 슬롯만 있고 벡터가 없으면 추천이 조용히 0건을
     * 내고, 벡터만 있고 슬롯이 없으면 추천 근거를 설명할 수 없다.
     */
    public MatchingIntentResponseDTO applyResult(Long sessionId, Long userId, IntentExtractResponse ai) {
        MatchingIntentSession session = sessionRepository.findById(sessionId)
          .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        Long taskId = session.getTask().getId();

        chatService.appendDomainReply(taskId, ai.getAssistantMessage());

        ExtractedDTO extracted = new ExtractedDTO(ai.getExtracted());
        boolean completed = ai.isCompleted();
        session.applyAiResult(nullToEmpty(ai.getMissingFields()), writeExtractedJson(extracted), completed);

        Long slotId = null;
        if (completed) {
            taskService.close(taskId, TaskCloseReason.COMPLETED);
            slotId = upsertSlot(userId, session, ai);
            upsertEmbedding(userId, ai.getEmbeddingVector());
        }

        return new MatchingIntentResponseDTO(sessionId, ai, slotId);
    }

    /**
     * 진행 중인 세션과 대화를 복원한다. 없으면 empty.
     */
    @Transactional(readOnly = true)
    public Optional<IntentSessionResponseDTO> getCurrentSession(Long userId) {
        return taskService.findActive(userId, RoutableDomain.MATCHING_INTENT)
          .flatMap(task -> sessionRepository.findByTaskId(task.getId()))
          .map(this::toSessionResponse);
    }

    /**
     * 진행 중인 세션을 버린다. 새 세션은 만들지 않는다 — 다음 메시지 때 자동 생성된다.
     *
     * <p>
     * 채팅 스레드는 건드리지 않는다. 스레드를 여러 개 갖고 골라 들어가게 되면서 "이 대화를
     * 닫는다"는 프론트가 새 스레드를 여는 것으로 표현되기 때문이다 — 여기서 스레드까지 닫으면
     * 사이드바에서 그 대화를 다시 열었을 때 이어 쓸 수 없게 된다.
     */
    public void restart(Long userId) {
        taskService.findActive(userId, RoutableDomain.MATCHING_INTENT)
          .ifPresent(task -> taskService.close(task.getId(), TaskCloseReason.ABANDONED));
    }

    // ── 내부 ──────────────────────────────────────────────────────────────
    /**
     * 사용자당 슬롯 1건 — 있으면 덮어쓴다.
     */
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
     * <p>
     * 차원 검증이 필수인 이유: vector(1536) 컬럼에 다른 길이를 넣으면 DB 예외가
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
        // 스레드 전체가 아니라 이 작업 소관만 돌려준다 — 게이트웨이의 되묻기 턴이나 다른 도메인
        // 발화까지 끼면 이 API 가 "의도 추출 대화"를 돌려준다는 기존 계약이 달라진다.
        List<IntentSessionResponseDTO.MessageDTO> messages
          = chatService.findTaskMessages(session.getTask().getId()).stream()
            .map(IntentSessionResponseDTO.MessageDTO::new)
            .toList();

        List<String> missingFields = nullToEmpty(session.getLastMissingFields());

        return new IntentSessionResponseDTO(
          session.getId(),
          IntentSessionStatus.of(session.getTask()),
          missingFields.isEmpty() && session.getLastExtractedJson() != null,
          missingFields,
          readExtractedJson(session.getLastExtractedJson()),
          messages
        );
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
          .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
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
