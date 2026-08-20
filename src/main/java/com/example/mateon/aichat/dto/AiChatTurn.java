package com.example.mateon.aichat.dto;

/**
 * 통합 로그에 이미 기록된 사용자 발화 한 턴을 가리키는 값 객체.
 *
 * <p>
 * 이게 있는 이유는 <b>이중 기록 방지</b>다. 게이트웨이가 위임을 하면 게이트웨이도, 도메인
 * 서비스도 같은 발화를 로그에 쓰려 든다. 그래서 기록은 게이트웨이가 한 번만 하고, 도메인
 * 서비스에는 "이미 적힌 이 턴을 처리해라"는 뜻으로 이 객체를 넘긴다.
 *
 * <p>
 * 엔티티가 아니라 id 만 담은 값이어야 한다 — TX1 이 커밋된 뒤 TX 밖에서 들고 다니므로
 * 엔티티를 넘기면 지연 로딩에서 터진다 (ConversationSnapshot 과 같은 이유).
 *
 * @param chatSessionId 이 발화가 속한 스레드
 * @param messageId 이미 저장된 USER 메시지 행의 id. 라우팅 확정 시 여기에 작업을 찍는다.
 */
public record AiChatTurn(Long chatSessionId, Long messageId) {

}
