package com.example.mateon.matching.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.config.AiServerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 별도 FastAPI AI 서버의 POST /intents/extract 를 호출한다.
 *
 * <p>AI 서버는 stateless — 매 호출마다 지금까지의 대화 전체를 보낸다. 추출/임베딩/문구 생성은
 * 전부 FastAPI 가 하고, 여기서는 요청을 만들어 보내고 응답을 받아오기만 한다.
 */
@Slf4j
@Component
public class IntentExtractionClient {

    private static final String EXTRACT_PATH = "/intents/extract";

    /** AI 서버가 요구하는 내부 인증 헤더. 값이 없거나 틀리면 AI 가 401 로 거절한다. */
    private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

    private final RestTemplate restTemplate;
    private final AiServerProperties properties;

    // @RequiredArgsConstructor 는 @Qualifier 를 못 붙이므로 생성자를 직접 작성한다.
    // 기본 restTemplate 빈(타임아웃 없음)이 아니라 AI 전용 빈을 주입받아야 한다.
    public IntentExtractionClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                                  AiServerProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * @param userMessages 사용자 발화만, 대화 순서대로. (AI 명세상 messages 는 "사용자가 한 말"만 담는다)
     */
    public IntentExtractResponse extract(List<String> userMessages) {
        try {
            // AI 스펙: id 는 1부터 순서대로 증가. 배열 순서가 곧 대화 순서.
            // DB 의 seq 를 그대로 쓰지 않고 여기서 재채번한다 (seq 는 ASSISTANT 행 때문에 건너뛴다).
            List<IntentExtractRequest.Message> messages = new ArrayList<>();
            for (int i = 0; i < userMessages.size(); i++) {
                messages.add(new IntentExtractRequest.Message(i + 1, userMessages.get(i)));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(INTERNAL_SECRET_HEADER, properties.getInternalSecret());

            ResponseEntity<IntentExtractResponse> response = restTemplate.exchange(
                    properties.getBaseUrl() + EXTRACT_PATH,
                    HttpMethod.POST,
                    new HttpEntity<>(new IntentExtractRequest(messages), headers),
                    IntentExtractResponse.class
            );

            IntentExtractResponse body = response.getBody();
            if (body == null || body.getAssistantMessage() == null) {
                log.warn("AI /intents/extract 응답이 비었거나 assistant_message 없음: {}", body);
                throw new MateonException(ErrorCode.AI_SERVER_ERROR);
            }
            return body;

        } catch (MateonException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // 연결 실패/타임아웃 → 재시도하면 될 수도 있는 일시 장애 (503)
            log.warn("AI 서버 연결 실패 또는 타임아웃: {}", e.getMessage());
            throw new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE);
        } catch (RestClientResponseException e) {
            // AI 가 4xx/5xx 로 응답. 원인이 전혀 다른 것들이라 로그를 갈라 준다 —
            // 안 그러면 502 만 보고 엉뚱하게 AI 서버를 뒤지게 된다.
            // (실서버 확인: 시크릿 불일치 → 401 "invalid internal secret",
            //               헤더 누락/본문 스키마 오류 → 422)
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                log.error("AI 서버가 인증을 거부했습니다 (status={}). .env 의 AI_INTERNAL_SECRET 이 " +
                        "AI 서버의 값과 일치하는지 확인하세요 ({} 헤더). body={}",
                        e.getStatusCode(), INTERNAL_SECRET_HEADER, e.getResponseBodyAsString());
            } else if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT) {
                log.error("AI 서버가 요청을 거부했습니다 (422). 요청 스키마가 AI 명세와 어긋납니다 " +
                        "(본문 형식 또는 필수 헤더 누락). body={}", e.getResponseBodyAsString());
            } else {
                log.warn("AI /intents/extract 호출 실패: status={}, body={}",
                        e.getStatusCode(), e.getResponseBodyAsString());
            }
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        } catch (Exception e) {
            log.warn("AI /intents/extract 호출 중 예외", e);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
    }
}
