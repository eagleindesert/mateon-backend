package com.example.mateon.events.client;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 별도 FastAPI AI 서버의 POST /internal/contests/embedding:refresh 를 호출한다.
 *
 * <p>
 * AI 서버는 stateless — 계산 결과만 반환하고 저장하지 않는다. 저장은 호출자
 * ({@code EventEmbeddingService}) 몫이다. 인증 헤더와 실패 처리는 {@link AiCallTemplate} 이
 * 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContestEmbeddingClient {

    private static final String PATH = "/internal/contests/embedding:refresh";

    private final AiCallTemplate ai;

    public ContestEmbeddingRefreshResponse refresh(ContestEmbeddingRefreshRequest request) {
        ContestEmbeddingRefreshResponse body =
          ai.post(PATH, request, ContestEmbeddingRefreshResponse.class);
        if (body.getEmbeddingVector() == null) {
            log.warn("AI {} 응답에 embedding_vector 가 없습니다", PATH);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
        return body;
    }
}
