package com.example.mateon.matching.service;

import com.example.mateon.matching.client.selection.SelectionEventClient;
import com.example.mateon.matching.client.selection.SelectionEventRequest;
import com.example.mateon.matching.dto.snapshot.SelectionSnapshot;
import com.example.mateon.matching.event.CandidateSelectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 선택 피드백 전송 흐름의 오케스트레이터.
 *
 * <p>
 * 클래스 레벨 @Transactional 이 없는 게 핵심이다 — {@link RecommendationService} 와 같은
 * 이유다 (FastAPI read-timeout 이 길어 TX 안에서 호출하면 커넥션 풀이 마른다). 조회는
 * RecommendationQueryService(readOnly), 선택 표시는 RecommendationLogService(@Transactional)
 * 가 맡고 그 뒤에 AI 를 호출한다. 빈이 나뉜 것도 필수다 — 같은 빈 안에서 호출하면 프록시를
 * 타지 않아 @Transactional 이 무시된다.
 *
 * <p>
 * 순서가 "표시 먼저, 전송 나중"인 이유: AI 서버가 죽어 있어도 <b>무엇이 선택됐는지는 우리
 * DB 에 남아야</b> 나중에 다시 보내거나 자체 분석을 할 수 있다. 반대로 두면 AI 장애가 곧
 * 선택 기록의 소실이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelectionEventService {

    private final RecommendationQueryService queryService;
    private final RecommendationLogService logService;
    private final SelectionEventClient client;

    /**
     * 선택 이벤트를 기록하고 AI 로 보낸다.
     *
     * <p>
     * 호출자는 이미 지원/제안을 성공적으로 마친 상태다. 여기서 무엇이 실패하든 그 발송을
     * 되돌리지 않는다 — 예외 처리는 리스너가 맡는다(그쪽 주석 참고).
     */
    public void record(CandidateSelectedEvent event) {
        // ① [TX1] 선택된 후보와 그때 노출됐던 목록을 스냅샷으로 → 커밋
        Optional<SelectionSnapshot> found = queryService.gatherSelection(
          event.direction(), event.chooserId(), event.selectedCandidateId());

        // 추천을 거치지 않고 곧바로 지원/제안한 경우. 기록할 "선택"이 없으므로 정상 종료다.
        if (found.isEmpty()) {
            log.debug("추천 이력이 없어 선택 피드백을 보내지 않습니다. direction={}, chooserId={}, candidateId={}",
              event.direction(), event.chooserId(), event.selectedCandidateId());
            return;
        }
        SelectionSnapshot snapshot = found.get();

        // ② [TX2] 우리 쪽 선택 표시. AI 호출보다 먼저다 (위 클래스 주석 참고).
        logService.markSelected(snapshot.getDirection(), snapshot.getSelectedItemId());

        // ③ [TX 밖] FastAPI 호출.
        client.send(buildRequest(snapshot, event.referenceId()));

        log.debug("선택 피드백 전송 완료. direction={}, candidateId={}, shown={}건",
          snapshot.getDirection(), snapshot.getSelectedCandidateId(),
          snapshot.getShownCandidates().size());
    }

    private SelectionEventRequest buildRequest(SelectionSnapshot snapshot, Long referenceId) {
        List<SelectionEventRequest.ShownCandidate> shown = snapshot.getShownCandidates().stream()
          .map(candidate -> new SelectionEventRequest.ShownCandidate(
          candidate.getCandidateId(),
          candidate.getTotalScore(),
          candidate.getComponentScores()))
          .toList();

        return new SelectionEventRequest(
          snapshot.getDirection(),
          snapshot.getSelectedCandidateId(),
          new SelectionEventRequest.SelectionContext(
            idempotencyKey(snapshot, referenceId),
            snapshot.getChooserFields(),
            shown));
    }

    /**
     * 멱등키. 랜덤 UUID 가 아니라 (방향, 지원서/제안 id) 에서 결정적으로 파생시킨다.
     *
     * <p>
     * 명세가 금지한 건 {@code proposal_id} 를 키로 <b>그대로</b> 쓰는 것이지 파생 자체가 아니다.
     * 랜덤을 쓰면 "재시도할 때도 같은 값"이라는 요구를 지키려고 키를 저장할 컬럼이 하나 더
     * 필요해진다. 지원/제안 모두 (팀, 상대) 쌍에 유니크 제약이 있어 같은 선택이면 같은 id 가
     * 나오므로, 이 파생값은 그 자체로 안정적이다.
     *
     * <p>
     * 방향을 앞에 붙이는 이유: 지원서 7번과 제안 7번은 서로 다른 사건이다.
     */
    private String idempotencyKey(SelectionSnapshot snapshot, Long referenceId) {
        String seed = snapshot.getDirection().name() + ":" + referenceId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
