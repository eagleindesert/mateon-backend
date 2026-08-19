package com.example.mateon.aichat.service;

import com.example.mateon.aichat.domain.*;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.repository.AiConversationMessageRepository;
import com.example.mateon.aichat.repository.AiConversationRepository;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * AI 대화 통합 로그의 DB 작업. 어떤 도메인에도 의존하지 않는다 — 게이트웨이도, matching 도
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
public class AiConversationService {

    private final AiConversationRepository conversationRepository;
    private final AiConversationMessageRepository messageRepository;
    private final UserRepository userRepository;

    /**
     * 진행 중인 대화를 찾거나 새로 만들고, 사용자 발화를 기록한다.
     *
     * <p>domain 은 일부러 비워 둔다 — 이 시점엔 어느 도메인인지 아직 모른다. 라우팅이 확정되면
     * {@link #assignDomain} 이 찍는다. 끝내 확정되지 않으면 null 로 남고, 그건 "게이트웨이가
     * 혼자 답한 턴"이라는 뜻이라 도메인 AI 로 새어 나가지 않는다.
     */
    public AiChatTurn appendUserMessage(Long userId, String message) {
        AiConversation conversation = resolveActiveConversation(userId);

        int nextSeq = messageRepository.countByConversationId(conversation.getId()) + 1;
        AiConversationMessage saved = messageRepository.save(
                new AiConversationMessage(conversation, nextSeq, AiChatRole.USER, message));

        return new AiChatTurn(conversation.getId(), saved.getId());
    }

    /**
     * 라우팅이 확정된 발화에 도메인과 그 도메인의 세션 id 를 찍는다.
     *
     * <p>위임할 곳이 있는 판정에만 부른다 — UNCLEAR/OUT_OF_SCOPE 는 domain 이 null 로 남아야 한다.
     */
    public void assignDomain(Long messageId, RoutableDomain domain, Long domainRefId) {
        messageRepository.findById(messageId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND))
                .assignDomain(domain, domainRefId);
    }

    /** 게이트웨이가 직접 답한 턴(되묻기·범위 밖 안내). domain 을 비워 둔다. */
    public void appendGatewayReply(Long conversationId, String content) {
        appendAssistantMessage(conversationId, content, null, null);
    }

    /** 도메인 AI 가 답한 턴. 그 도메인 세션 소관으로 기록한다. */
    public void appendDomainReply(Long conversationId, String content,
                                  RoutableDomain domain, Long domainRefId) {
        appendAssistantMessage(conversationId, content, domain, domainRefId);
    }

    /**
     * 특정 도메인 세션에 속한 사용자 발화만 순서대로. 도메인 AI 로 보낼 배열의 재료다.
     *
     * <p>읽기 전용이고 TX 밖에서 쓰이므로 문자열만 뽑는다.
     */
    @Transactional(readOnly = true)
    public List<String> findUserContents(RoutableDomain domain, Long domainRefId) {
        return messageRepository.findContentsByDomainRefAndRole(domain, domainRefId, AiChatRole.USER);
    }

    /** 특정 도메인 세션의 대화 전체 (USER + ASSISTANT). 그 도메인의 복원 API 가 쓴다. */
    @Transactional(readOnly = true)
    public List<AiConversationMessage> findDomainMessages(RoutableDomain domain, Long domainRefId) {
        return messageRepository.findByDomainAndDomainRefIdOrderBySeqAsc(domain, domainRefId);
    }

    /** 진행 중인 대화를 끝낸다. 없으면 아무 일도 하지 않는다 (재시작은 멱등이어야 한다). */
    public void closeActive(Long userId) {
        conversationRepository.findByUserIdAndStatus(userId, AiConversationStatus.ACTIVE)
                .ifPresent(AiConversation::close);
    }

    /** 도메인 세션을 대화에 묶을 때 쓸 참조. */
    @Transactional(readOnly = true)
    public AiConversation requireConversation(Long conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private void appendAssistantMessage(Long conversationId, String content,
                                        RoutableDomain domain, Long domainRefId) {
        AiConversation conversation = requireConversation(conversationId);

        int nextSeq = messageRepository.countByConversationId(conversationId) + 1;
        AiConversationMessage message = new AiConversationMessage(
                conversation, nextSeq, AiChatRole.ASSISTANT, content);
        message.assignDomain(domain, domainRefId);
        messageRepository.save(message);
    }

    /**
     * 진행 중인 대화를 찾거나 새로 만든다.
     *
     * <p>대화에는 TTL 만료가 없다 — 방치 판정은 도메인이 할 일이고(matching 은
     * ai.session-ttl 로 자기 세션을 만료시킨다), 로그 자체는 오래됐다고 버릴 이유가 없다.
     * 매칭 세션이 만료돼 새로 열려도 같은 대화에 이어 붙고, domain_ref_id 가 달라서 섞이지 않는다.
     */
    private AiConversation resolveActiveConversation(Long userId) {
        Optional<AiConversation> active =
                conversationRepository.findByUserIdAndStatus(userId, AiConversationStatus.ACTIVE);
        if (active.isPresent()) {
            return active.get();
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
        return conversationRepository.save(new AiConversation(user));
    }
}
