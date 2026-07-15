package com.example.mateon.matching.dto.response;

import com.example.mateon.matching.client.IntentExtractResponse;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * POST /messages 응답. AI 응답을 프론트가 쓸 수 있는 형태로 다듬은 것.
 *
 * <p>embeddingText/embeddingVector 는 일부러 담지 않는다 — 1536 개 float 를 프론트에 흘릴
 * 이유가 없다 (~20KB).
 */
@Getter
public class MatchingIntentResponseDTO {

    private final Long sessionId;

    /** true 면 missingFields 가 비어있고 slotId 가 채워진다. */
    private final boolean completed;

    /**
     * AI 가 준 snake_case 문자열 그대로 (예: "desired_roles").
     * extracted 는 camelCase 로 변환하는데 여기만 snake_case 인 건 의도적이다 —
     * missingFields 의 값은 AI 스펙이 계약이고, extracted 의 키는 우리 API 의 스키마다.
     */
    private final List<String> missingFields;

    private final ExtractedDTO extracted;

    /** 프론트가 그대로 화면에 보여주면 되는 챗봇 문구. */
    private final String assistantMessage;

    /** 완료 시에만 채워진다. */
    private final Long slotId;

    public MatchingIntentResponseDTO(Long sessionId, IntentExtractResponse ai, Long slotId) {
        this.sessionId = sessionId;
        this.completed = ai.isCompleted();
        this.missingFields = ai.getMissingFields() != null ? ai.getMissingFields() : Collections.emptyList();
        this.extracted = new ExtractedDTO(ai.getExtracted());
        this.assistantMessage = ai.getAssistantMessage();
        this.slotId = slotId;
    }

    /** GET /session 복원용 — AI 재호출 없이 DB 에 저장해 둔 마지막 결과로 만든다. */
    public MatchingIntentResponseDTO(Long sessionId, boolean completed, List<String> missingFields,
                                     ExtractedDTO extracted, String assistantMessage, Long slotId) {
        this.sessionId = sessionId;
        this.completed = completed;
        this.missingFields = missingFields != null ? missingFields : Collections.emptyList();
        this.extracted = extracted;
        this.assistantMessage = assistantMessage;
        this.slotId = slotId;
    }
}
