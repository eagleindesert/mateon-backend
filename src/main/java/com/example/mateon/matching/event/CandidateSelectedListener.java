package com.example.mateon.matching.event;

import com.example.mateon.matching.service.SelectionEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 선택 피드백을 AI 서버로 보낸다. teams 도메인의 발송 흐름과 matching 도메인의 추천 이력을
 * 잇는 자리다.
 *
 * <p>
 * TeamEmbeddingRefreshListener 와 같은 규약이다:
 * <ul>
 *   <li>AFTER_COMMIT: 커밋되지 않은 지원/제안으로 선택을 기록하지 않는다 (롤백 시 이벤트 무시).</li>
 *   <li>@Async: AI 호출이 수십 초 걸릴 수 있어 지원/제안 응답을 붙잡으면 안 된다.</li>
 *   <li>실패는 warn 만. 명세도 "로깅 실패가 제안 생성이나 사용자 화면 흐름을 막아서는 안 된다"고
 *       못박고 있다.</li>
 * </ul>
 *
 * <p>
 * <b>예외를 밖으로 내보내면 안 된다.</b> AiCallTemplate 은 AI 가 죽었을 때 MateonException 을
 * 던지므로 여기서 반드시 삼켜야 한다. 삼키지 않으면 @Async 스레드에서 스택트레이스만 남지만,
 * 그 소음이 "지원이 실패했다"로 오독되기 쉽다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateSelectedListener {

    private final SelectionEventService selectionEventService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCandidateSelected(CandidateSelectedEvent event) {
        try {
            selectionEventService.record(event);
        } catch (Exception e) {
            log.warn("선택 피드백 전송 실패 (지원/제안 자체에는 영향 없음). "
              + "direction={}, chooserId={}, candidateId={}",
              event.direction(), event.chooserId(), event.selectedCandidateId(), e);
        }
    }
}
