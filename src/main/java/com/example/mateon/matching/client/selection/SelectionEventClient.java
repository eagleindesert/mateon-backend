package com.example.mateon.matching.client.selection;

import com.example.mateon.common.ai.AiCallTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 별도 FastAPI AI 서버의 선택 피드백 엔드포인트를 호출한다.
 *
 * <ul>
 *   <li>POST /selection-events — 실제로 고른 후보와 그때 노출된 추천 목록을 기록 요청</li>
 * </ul>
 *
 * <p>
 * 추천 엔드포인트들과 별개의 빈으로 둔 이유: {@code RecommendationClient} 는 "점수를 받아
 * 사용자에게 보여주는" 동기 경로고, 이쪽은 아무도 기다리지 않는 사후 기록이다. 실패했을 때
 * 해야 할 일이 정반대라(저쪽은 502, 이쪽은 warn 후 종료) 섞어 두면 규약이 흐려진다.
 *
 * <p>
 * 인증 헤더와 4xx/5xx 진단 로그는 {@link AiCallTemplate} 이 담당한다.
 *
 * <p>
 * <b>여기서 예외를 삼키지는 않는다.</b> 호출자(선택 피드백 서비스/리스너)가 삼킨다 — 이
 * 클래스는 "보냈고 이렇게 실패했다"까지만 알리고, 그 실패를 무시할지는 흐름을 아는 쪽이
 * 정하는 게 맞다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelectionEventClient {

    private static final String SELECTION_EVENTS_PATH = "/selection-events";

    private final AiCallTemplate ai;

    /**
     * 선택 이벤트를 보낸다.
     *
     * <p>
     * 응답의 accepted 를 검증해 예외로 만들지 않는다 — 이건 저장 확인이 아니라 접수 확인이라
     * (SelectionEventResponse 주석) false 라고 우리가 할 수 있는 일이 없다. 대신 기대와 다른
     * 응답은 로그로 남겨 AI 쪽 스키마 변경을 눈치챌 수 있게 한다.
     */
    public void send(SelectionEventRequest request) {
        SelectionEventResponse body =
          ai.post(SELECTION_EVENTS_PATH, request, SelectionEventResponse.class);

        if (body.getAccepted() == null) {
            log.warn("AI {} 응답에 accepted 가 없습니다. 스키마가 바뀌었는지 확인하세요.",
              SELECTION_EVENTS_PATH);
        } else if (!body.getAccepted()) {
            log.warn("AI {} 가 선택 이벤트를 접수하지 않았습니다 (accepted=false).",
              SELECTION_EVENTS_PATH);
        }
    }
}
