package com.example.mateon.matching.dto.snapshot;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 상세 이유 생성에 필요한 모든 것. 조회 TX 가 커밋된 뒤 TX 밖에서 FastAPI 를 호출할 때 넘긴다
 * (다른 스냅샷들과 같은 역할 — {@link ConversationSnapshot} 주석 참고).
 *
 * <p>다른 스냅샷과 달리 <b>엔티티를 하나도 담지 않는다</b>. 요약 문자열 조립까지 조회 TX 안에서
 * 끝내기 때문이다 — 폴백 층이 slot/user/embedding/team 네 엔티티를 오가는데, 그걸 TX 밖으로
 * 들고 나가면 LazyInitializationException 의 표면적만 넓어진다.
 */
@Getter
@RequiredArgsConstructor
public class ReasonSnapshot {

    /** 이유를 캐시해 넣을 추천 아이템의 id. */
    private final Long itemId;

    /** 선택된 상대 쪽 요약. */
    private final String candidateSummary;

    /** 질의 주체 쪽 요약. */
    private final String targetSummary;

    /** 점수 구성 서술. */
    private final String scoreContext;

    /**
     * 이미 생성해 둔 이유. non-null 이면 AI 를 부르지 않고 이 값을 그대로 돌려준다.
     * null 은 "이유가 없다"가 아니라 "아직 만든 적 없다"는 뜻이다.
     */
    private final String cachedReason;

    /** 캐시 hit 여부. AI 호출을 건너뛸지 판단하는 유일한 기준이다. */
    public boolean hasCachedReason() {
        return cachedReason != null && !cachedReason.isBlank();
    }
}
