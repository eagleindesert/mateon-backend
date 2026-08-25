package com.example.mateon.events.client;

import com.example.mateon.common.ai.AiCallTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 공모전 포스터 이미지를 AI 서버로 보내 정보를 추출한다 (POST /contests/extract-image).
 * AI 서버는 아무것도 저장하지 않으며 계산 결과만 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class ContestImageExtractionClient {

    private static final String PATH = "/contests/extract-image";

    /**
     * AI 명세가 지정한 파트 이름. 다르면 FastAPI 가 422 를 낸다.
     */
    private static final String FILE_PART_NAME = "img_file";

    private final AiCallTemplate aiCallTemplate;

    public ContestExtractResponse extract(byte[] imageBytes, String filename, String contentType) {
        // ByteArrayResource 는 기본적으로 파일명이 없다. 파일명이 없으면 멀티파트 파트에
        // filename 파라미터가 빠지고, FastAPI 는 그 파트를 UploadFile 이 아닌 일반 폼 필드로
        // 취급해 422 로 거절한다. 그래서 getFilename() 을 덮어쓴다.
        ByteArrayResource filePart = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(FILE_PART_NAME, new HttpEntity<>(filePart, partHeaders));

        // 실패 매핑(503/502)은 AiCallTemplate 규약 그대로다.
        return aiCallTemplate.postMultipart(PATH, parts, ContestExtractResponse.class);
    }
}
