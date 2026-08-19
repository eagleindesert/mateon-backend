package com.example.mateon.aichat.service;

import com.example.mateon.aichat.domain.*;
import com.example.mateon.aichat.dto.AiChatSessionSummary;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.repository.AiChatMessageRepository;
import com.example.mateon.aichat.repository.AiChatSessionRepository;
import com.example.mateon.aichat.repository.AiDomainTaskRepository;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * AI 채팅 스레드와 메시지의 DB 작업. 어떤 도메인에도 의존하지 않는다 — 게이트웨이도, matching 도
 * 여기를 통해서만 대화를 읽고 쓴다.
 *
 * <p><b>이 클래스가 유일한 쓰기 경로여야 한다.</b> 게이트웨이가 발화를 기록하고 도메인 서비스도
 * 따로 기록하면 같은 문장이 두 벌 남는다. 그래서 사용자 발화는 {@link #appendUserMessage} 한
 * 곳에서만 저장하고, 도메인 서비스에는 이미 저장된 턴({@link AiChatTurn})을 넘긴다.
 *
 * <p>메서드마다 트랜잭션이 하나씩이다. 호출자(게이트웨이/MatchingIntentService)는 LLM 을
 * 부르느라 클래스 레벨 @Transactional 이 없고, 그 사이사이에 여기를 짧게 호출한다.
 * 이미 트랜잭션 안에 있는 호출자(MatchingIntentSessionService)가 부르면 그 트랜잭션에 합류한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AiChatService {

    /** 사이드바 한 화면. 커서 페이지네이션은 필요해지면 붙인다. */
    private static final int SESSION_PAGE_SIZE = 50;

    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final AiDomainTaskRepository taskRepository;
    private final UserRepository userRepository;

    /** 빈 스레드를 연다. 프론트가 "새 대화"를 누르면 여기로 온다. */
    public AiChatSession createSession(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
        return sessionRepository.save(new AiChatSession(user));
    }

    /**
     * 사용자 발화를 지정된 스레드에 기록한다.
     *
     * <p><b>스레드 소유권을 검증하는 유일한 지점이다.</b> sessionId 를 프론트가 보내므로, 남의
     * 스레드에 글을 쓰는 걸 여기서 막아야 한다. 없는 스레드와 남의 스레드를 같은 404 로 처리하는
     * 건 의도적이다 — 403 을 주면 남의 스레드가 존재한다는 사실이 새어 나간다.
     *
     * <p>도메인 작업은 일부러 비워 둔다 — 이 시점엔 어느 도메인인지 아직 모른다. 라우팅이
     * 확정되면 {@link #assignTask} 가 찍는다. 끝내 확정되지 않으면 null 로 남고, 그건
     * "게이트웨이가 혼자 답한 턴"이라는 뜻이라 도메인 AI 로 새어 나가지 않는다.
     */
    public AiChatTurn appendUserMessage(Long userId, Long chatSessionId, String message) {
        AiChatSession session = requireOwnedSession(userId, chatSessionId);
        session.titleFrom(message);

        AiChatMessage saved = messageRepository.save(
                new AiChatMessage(session, session.nextSeq(), AiChatRole.USER, message));

        return new AiChatTurn(session.getId(), saved.getId());
    }

    /**
     * 라우팅이 확정된 발화에 도메인 작업을 찍는다.
     *
     * <p>위임할 곳이 있는 판정에만 부른다 — UNCLEAR/OUT_OF_SCOPE 는 task 가 null 로 남아야 한다.
     */
    public void assignTask(Long messageId, Long taskId) {
        AiChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        message.assignTask(requireTask(taskId));
    }

    /** 게이트웨이가 직접 답한 턴(되묻기·범위 밖 안내). 작업 없이 남는다. */
    public void appendGatewayReply(Long chatSessionId, String content) {
        AiChatSession session = requireSession(chatSessionId);
        messageRepository.save(
                new AiChatMessage(session, session.nextSeq(), AiChatRole.ASSISTANT, content));
    }

    /**
     * 도메인 AI 가 답한 턴. 그 작업 소관으로 기록한다.
     *
     * <p>스레드 id 를 따로 받지 않는다 — 작업이 이미 자기 스레드를 알고 있고, 둘을 따로 받으면
     * 어긋난 조합을 넘길 수 있다.
     */
    public void appendDomainReply(Long taskId, String content) {
        AiDomainTask task = requireTask(taskId);
        AiChatSession session = requireSession(task.getChatSession().getId());

        AiChatMessage message = new AiChatMessage(
                session, session.nextSeq(), AiChatRole.ASSISTANT, content);
        message.assignTask(task);
        messageRepository.save(message);
    }

    /**
     * 특정 도메인 작업에 속한 사용자 발화만 순서대로. 도메인 AI 로 보낼 배열의 재료다.
     *
     * <p>읽기 전용이고 TX 밖에서 쓰이므로 문자열만 뽑는다.
     */
    @Transactional(readOnly = true)
    public List<String> findUserContents(Long taskId) {
        return messageRepository.findContentsByTaskAndRole(taskId, AiChatRole.USER);
    }

    /** 특정 도메인 작업의 대화 전체 (USER + ASSISTANT). 그 도메인의 복원 API 가 쓴다. */
    @Transactional(readOnly = true)
    public List<AiChatMessage> findTaskMessages(Long taskId) {
        return messageRepository.findByTaskIdOrderBySeqAsc(taskId);
    }

    /** 스레드 하나를 통째로 복원한다. 게이트웨이 턴도 포함된다. */
    @Transactional(readOnly = true)
    public List<AiChatMessage> findSessionMessages(Long userId, Long chatSessionId) {
        requireOwnership(userId, sessionRepository.findById(chatSessionId));
        return messageRepository.findByChatSessionIdOrderBySeqAsc(chatSessionId);
    }

    /** 사이드바 목록. 최근에 쓴 순. */
    @Transactional(readOnly = true)
    public List<AiChatSessionSummary> listSessions(Long userId) {
        return sessionRepository.findSummariesByUserId(userId, PageRequest.of(0, SESSION_PAGE_SIZE));
    }

    /**
     * 가장 최근 스레드를 쓰되 없으면 만든다.
     *
     * <p>스레드를 지정하지 않는 레거시 경로({@code POST /api/matching/intents/messages}) 전용이다.
     * 게이트웨이를 거쳐 들어오는 발화는 항상 스레드가 지정돼 있으므로 이 길로 오지 않는다.
     */
    public AiChatSession findOrCreateLatestSession(Long userId) {
        return sessionRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
                .orElseGet(() -> createSession(userId));
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    /**
     * 채번을 위해 행 잠금을 잡고 가져온다. <b>메시지를 붙이는 경로만</b> 이걸 쓴다.
     *
     * <p>읽기 경로에서 부르면 안 된다 — {@code @Transactional(readOnly = true)} 안에서
     * {@code SELECT ... FOR UPDATE} 를 내보내면 Postgres 가 거절해서 500 이 된다. 리포지토리를
     * 목으로 세운 단위 테스트로는 드러나지 않으므로, 잠금 여부를 메서드 이름으로 갈라 둔다.
     */
    private AiChatSession requireSession(Long chatSessionId) {
        return sessionRepository.findWithLockById(chatSessionId)
                .orElseThrow(() -> new MateonException(ErrorCode.AI_CHAT_SESSION_NOT_FOUND));
    }

    private AiChatSession requireOwnedSession(Long userId, Long chatSessionId) {
        return requireOwnership(userId, sessionRepository.findWithLockById(chatSessionId));
    }

    /**
     * 없는 스레드와 남의 스레드를 같은 예외로 처리한다 — 갈라 주면 스레드 id 를 훑어 남이 대화를
     * 몇 개 갖고 있는지 알아낼 수 있다.
     */
    private AiChatSession requireOwnership(Long userId, Optional<AiChatSession> found) {
        return found.filter(session -> session.isOwnedBy(userId))
                .orElseThrow(() -> new MateonException(ErrorCode.AI_CHAT_SESSION_NOT_FOUND));
    }

    private AiDomainTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
