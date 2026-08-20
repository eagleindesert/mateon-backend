package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.service.AiChatService;
import com.example.mateon.matching.client.intent.IntentExtractResponse;
import com.example.mateon.matching.client.intent.IntentExtractionClient;
import com.example.mateon.matching.dto.response.IntentSessionResponseDTO;
import com.example.mateon.matching.dto.snapshot.ConversationSnapshot;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 의도 추출 흐름의 오케스트레이터.
 *
 * <p>
 * 클래스 레벨 @Transactional 이 없는 게 핵심이다. FastAPI 호출은 read-timeout 이 60 초라,
 * 트랜잭션 안에서 하면 그동안 DB 커넥션과 행 잠금을 붙들어 커넥션 풀이 마른다. 그래서 DB 작업은
 * MatchingIntentSessionService(@Transactional)에 맡기고 그 사이에서 AI 를 호출한다.
 *
 * <p>
 * 빈이 둘로 나뉜 것도 필수다 — 같은 빈 안에서 호출하면 프록시를 타지 않아 @Transactional 이
 * 무시된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingIntentService {

    private final MatchingIntentSessionService sessionService;
    private final AiChatService chatService;
    private final IntentExtractionClient client;

    /**
     * 직접 진입점 — 기존 {@code POST /api/matching/intents/messages} 가 쓴다.
     * 발화를 통합 로그에 기록한 뒤 아래 오버로드로 넘긴다.
     *
     * <p>
     * 게이트웨이를 거치지 않고 들어온 발화도 통합 로그에 남는다 — 대화 이력이 유입 경로에
     * 따라 갈리면 로그를 한 곳에 모은 의미가 없다.
     *
     * <p>
     * 이 경로는 스레드를 지정하지 않는다(프론트가 스레드를 모르던 시절의 API 다). 그래서
     * 가장 최근 스레드에 이어 붙이고, 하나도 없으면 만든다.
     */
    public MatchingIntentResponseDTO submitMessage(Long userId, String message) {
        AiChatSession session = chatService.findOrCreateLatestSession(userId);
        return submitTurn(userId, chatService.appendUserMessage(userId, session.getId(), message));
    }

    /**
     * 게이트웨이 진입점 — 이미 통합 로그에 적힌 턴을 받는다.
     *
     * <p>
     * 문자열이 아니라 턴을 받는 이유는 이중 기록 방지다. 게이트웨이는 라우팅을 판단하려고
     * 위임 전에 이미 발화를 기록했으므로, 여기서 또 쓰면 같은 문장이 두 벌 남는다.
     *
     * <p>
     * {@code submitMessage} 의 오버로드로 두지 않은 건 의도적이다 — {@code (Long, ?)} 두 개가
     * 되면 호출부와 목 스텁에서 어느 쪽인지 모호해진다.
     */
    public MatchingIntentResponseDTO submitTurn(Long userId, AiChatTurn turn) {
        // ① [TX1] 세션 확보(없거나 만료/완료면 새로) + 이 턴을 세션 소관으로 표시 → 커밋
        ConversationSnapshot snapshot = sessionService.bindTurn(userId, turn);

        // ② [TX 밖] FastAPI 호출. 수십 초가 걸려도 DB 커넥션을 잡고 있지 않다.
        //    실패하면 여기서 예외가 나가고 ①에서 표시한 사용자 메시지는 남는다 — 의도된 동작이다.
        //    채팅 로그로서 옳고, AI 가 stateless 라 다음 호출에 전체 배열을 다시 보내므로
        //    재시도가 자연히 이어진다.
        IntentExtractResponse ai = client.extract(snapshot.getUserMessages());

        // ③ [TX2] ASSISTANT 메시지 + 진행상황 갱신 + (완료 시) 슬롯/임베딩 upsert → 커밋
        return sessionService.applyResult(snapshot.getSessionId(), userId, ai);
    }

    public Optional<IntentSessionResponseDTO> getCurrentSession(Long userId) {
        return sessionService.getCurrentSession(userId);
    }

    public void restart(Long userId) {
        sessionService.restart(userId);
    }
}
