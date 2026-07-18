package com.example.mateon.matching.service;

import com.example.mateon.matching.client.IntentExtractResponse;
import com.example.mateon.matching.client.IntentExtractionClient;
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
 * <p>클래스 레벨 @Transactional 이 없는 게 핵심이다. FastAPI 호출은 read-timeout 이 60 초라,
 * 트랜잭션 안에서 하면 그동안 DB 커넥션과 행 잠금을 붙들어 커넥션 풀이 마른다. 그래서 DB 작업은
 * MatchingIntentSessionService(@Transactional)에 맡기고 그 사이에서 AI 를 호출한다.
 *
 * <p>빈이 둘로 나뉜 것도 필수다 — 같은 빈 안에서 호출하면 프록시를 타지 않아 @Transactional 이
 * 무시된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingIntentService {

    private final MatchingIntentSessionService sessionService;
    private final IntentExtractionClient client;

    public MatchingIntentResponseDTO submitMessage(Long userId, String message) {
        // ① [TX1] 세션 확보(없거나 만료/완료면 새로) + USER 메시지 저장 → 커밋
        ConversationSnapshot snapshot = sessionService.appendUserMessage(userId, message);

        // ② [TX 밖] FastAPI 호출. 수십 초가 걸려도 DB 커넥션을 잡고 있지 않다.
        //    실패하면 여기서 예외가 나가고 ①에서 저장한 사용자 메시지는 남는다 — 의도된 동작이다.
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
