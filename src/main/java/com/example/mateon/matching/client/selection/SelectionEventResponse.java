package com.example.mateon.matching.client.selection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * FastAPI POST /selection-events 응답 본문.
 *
 * <p>
 * {@code accepted} 는 <b>저장 성공이 아니라 접수 확인</b>이다 — AI 서버는 검증만 끝나면 저장을
 * 기다리지 않고 바로 true 를 돌려준다. 그래서 이 값을 근거로 "기록됐다"고 단정하거나 false 라고
 * 재시도할 게 아니다. 우리 쪽 선택 기록은 recommendation_items.selected_at 이 따로 남긴다.
 *
 * <p>
 * @JsonNaming 을 쓰지 않는 이유, @JsonIgnoreProperties 를 클래스에 명시하는 이유는
 * {@code IntentExtractResponse} 참조.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SelectionEventResponse {

    /**
     * 접수 여부. 래퍼 타입인 이유는 "AI 가 이 필드를 안 줬다"와 "false 를 줬다"를 구분하기
     * 위해서다 (전자는 스키마가 어긋난 것이라 로그 문구가 달라야 한다).
     */
    private Boolean accepted;
}
