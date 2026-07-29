package com.example.mateon.portfolios.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.client.AiCallTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * 포트폴리오 PDF 를 AI 서버로 보내 요약을 받는다 (POST /portfolios/summarize).
 *
 * <p>AI 서버는 PDF 를 페이지별 이미지로 렌더링해 한 번의 Vision 호출로 읽는다(기본 15페이지까지).
 * 아무것도 저장하지 않으며 계산 결과만 돌려준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioSummaryClient {

    private static final String PATH = "/portfolios/summarize";

    /** AI 명세가 지정한 파트 이름. 다르면 FastAPI 가 422 를 낸다. */
    private static final String FILE_PART_NAME = "pdf_file";

    private final AiCallTemplate aiCallTemplate;

    /**
     * @param filename 사용자가 올린 원본 파일명. 없으면 AI 가 파트를 UploadFile 로 인식하지 못하므로
     *                 호출자가 비워 보내는 일이 없어야 한다.
     * @throws MateonException AI_SERVER_UNAVAILABLE(503) / AI_SERVER_ERROR(502)
     */
    public PortfolioSummaryResponse summarize(byte[] pdfBytes, String filename) {
        // ByteArrayResource 는 기본적으로 파일명이 없다. 파일명이 없으면 멀티파트 파트에
        // filename 파라미터가 빠지고, FastAPI 는 그 파트를 UploadFile 이 아닌 일반 폼 필드로
        // 취급해 422 로 거절한다. 그래서 getFilename() 을 덮어쓴다.
        ByteArrayResource filePart = new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(FILE_PART_NAME, new HttpEntity<>(filePart, partHeaders));

        // 실패 매핑(503/502)은 AiCallTemplate 규약 그대로다.
        PortfolioSummaryResponse response =
                aiCallTemplate.postMultipart(PATH, parts, PortfolioSummaryResponse.class);

        // 스키마별 필수 필드 검증은 각 클라이언트 몫이다 — AiCallTemplate 은 "본문이 왔는가"까지만 본다.
        // 요약이 비어 있으면 사용자에게 보여 줄 것도, 캐시에 넣을 것도 없다.
        if (!StringUtils.hasText(response.getResponse())) {
            log.warn("AI {} 가 빈 요약을 반환했습니다 (pdf_id={})", PATH, response.getPdfId());
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
        return response;
    }
}
