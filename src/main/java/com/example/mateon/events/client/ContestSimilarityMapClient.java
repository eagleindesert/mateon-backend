package com.example.mateon.events.client;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 별도 FastAPI AI 서버의 POST /contests/similarity-map 를 호출한다.
 *
 * <p>
 * AI 서버는 후보를 조회하지 않는다. 기준·후보 임베딩을 실어 보내는 것과 결과를 프론트
 * DTO 로 옮기는 것은 호출자 몫이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContestSimilarityMapClient {

    private static final String PATH = "/contests/similarity-map";

    private final AiCallTemplate ai;

    public ContestSimilarityMapResponse map(ContestSimilarityMapRequest request) {
        ContestSimilarityMapResponse body =
          ai.post(PATH, request, ContestSimilarityMapResponse.class);
        if (body.getQuery() == null || body.getPoints() == null) {
            log.warn("AI {} 응답에 query 또는 points 가 없습니다", PATH);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
        return body;
    }
}
