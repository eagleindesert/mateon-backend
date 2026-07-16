package com.example.mateon.teams.client;

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

/**
 * 별도 FastAPI AI 서버의 POST /internal/teams/embedding:refresh 를 호출한다.
 *
 * <p>AI 서버는 stateless — 계산 결과만 반환하고 저장하지 않는다 (같은 입력이면 같은 출력,
 * 멱등). 임베딩/메타데이터 추출은 전부 FastAPI 가 하고, 여기서는 요청을 만들어 보내고
 * 응답을 받아오기만 한다. 저장은 호출자(TeamEmbeddingService) 몫.
 *
 * <p>예외는 IntentExtractionClient 와 같은 규약으로 던지지만, 호출자가 비동기 리스너라
 * 최종적으로는 warn 로그로 끝난다 (팀 저장에는 영향 없음).
 */
@Slf4j
@Component
public class TeamEmbeddingClient {

    private static final String REFRESH_PATH = "/internal/teams/embedding:refresh";

    /** AI 서버가 요구하는 내부 인증 헤더. 값이 없거나 틀리면 AI 가 401 로 거절한다. */
    private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

    private final RestTemplate restTemplate;
    private final AiServerProperties properties;

    // @RequiredArgsConstructor 는 @Qualifier 를 못 붙이므로 생성자를 직접 작성한다.
    // 기본 restTemplate 빈(타임아웃 없음)이 아니라 AI 전용 빈을 주입받아야 한다.
    public TeamEmbeddingClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                               AiServerProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public TeamEmbeddingRefreshResponse refresh(TeamEmbeddingRefreshRequest request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(INTERNAL_SECRET_HEADER, properties.getInternalSecret());

            ResponseEntity<TeamEmbeddingRefreshResponse> response = restTemplate.exchange(
                    properties.getBaseUrl() + REFRESH_PATH,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    TeamEmbeddingRefreshResponse.class
            );

            TeamEmbeddingRefreshResponse body = response.getBody();
            if (body == null || body.getEmbeddingVector() == null) {
                log.warn("AI {} 응답이 비었거나 embedding_vector 없음: {}", REFRESH_PATH, body);
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
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                log.error("AI 서버가 인증을 거부했습니다 (status={}). .env 의 AI_INTERNAL_SECRET 이 " +
                        "AI 서버의 값과 일치하는지 확인하세요 ({} 헤더). body={}",
                        e.getStatusCode(), INTERNAL_SECRET_HEADER, e.getResponseBodyAsString());
            } else if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT) {
                log.error("AI 서버가 요청을 거부했습니다 (422). 요청 스키마가 AI 명세와 어긋납니다 " +
                        "(본문 형식 또는 필수 헤더 누락). body={}", e.getResponseBodyAsString());
            } else {
                log.warn("AI {} 호출 실패: status={}, body={}",
                        REFRESH_PATH, e.getStatusCode(), e.getResponseBodyAsString());
            }
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        } catch (Exception e) {
            log.warn("AI {} 호출 중 예외", REFRESH_PATH, e);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
    }
}
