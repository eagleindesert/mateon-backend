package com.example.mateon.matching.dto.snapshot;

import com.example.mateon.matching.client.intent.IntentExtractRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * TX1 이 커밋된 뒤 TX 밖에서 FastAPI 를 호출할 때 넘기는 값 객체.
 *
 * <p>엔티티가 아니라 detach 된 값이어야 한다 — TX1 이 이미 커밋됐으므로 엔티티를 넘겨서
 * 지연 로딩을 건드리면 LazyInitializationException 이 난다. 이 구조의 유일한 함정이다.
 *
 * <p>※ 같은 dto 패키지에 있지만 request/response 와 성격이 다르다 — 서비스 사이에서만 오가고
 * 직렬화되지 않는다.
 */
@Getter
@RequiredArgsConstructor
public class ConversationSnapshot {

    private final Long sessionId;

    /**
     * FastAPI 로 보낼 배열. 접두(있으면) 다음 일반 턴(USER+ASSISTANT). id 는 클라이언트가
     * 보내기 직전에 1..N 으로 다시 매긴다.
     */
    private final List<IntentExtractRequest.Message> messages;
}
