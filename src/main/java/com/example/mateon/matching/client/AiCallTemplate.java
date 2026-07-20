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

/**
 * FastAPI AI 서버로 POST 를 보내는 공통 호출부. 인증 헤더와 실패 처리 규약이 여기 한 벌만 있다.
 *
 * <p>원래 {@link RecommendationClient} 안의 private 메서드였는데, 제안 조립(/proposals/*)이
 * 같은 규약을 필요로 하면서 밖으로 뺐다. 복사하지 않은 이유는 그 클래스 주석에 적힌 그대로다 —
 * 클라이언트를 복사하면 401/422 진단 로그가 여러 벌이 되고 한쪽만 고쳐지는 사고가 난다.
 *
 * <p>여기서 보는 건 "본문이 왔는가"까지다. 스키마별 필수 필드 검증은 각 클라이언트가 한다
 * (이 클래스는 어떤 응답 타입인지 모른다).
 */
@Slf4j
@Component
public class AiCallTemplate {

    /** AI 서버가 요구하는 내부 인증 헤더. 값이 없거나 틀리면 AI 가 401 로 거절한다. */
    private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

    private final RestTemplate restTemplate;
    private final AiServerProperties properties;

    // @RequiredArgsConstructor 는 @Qualifier 를 못 붙이므로 생성자를 직접 작성한다.
    // 기본 restTemplate 빈(타임아웃 없음)이 아니라 AI 전용 빈을 주입받아야 한다.
    public AiCallTemplate(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                          AiServerProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * @param path         AI 서버 기준 경로 (예: "/recommendations/reason")
     * @param responseType 역직렬화할 응답 타입
     * @return null 이 아닌 응답 본문
     * @throws MateonException AI_SERVER_UNAVAILABLE(연결/타임아웃) 또는 AI_SERVER_ERROR
     */
    public <T> T post(String path, Object request, Class<T> responseType) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(INTERNAL_SECRET_HEADER, properties.getInternalSecret());

            ResponseEntity<T> response = restTemplate.exchange(
                    properties.getBaseUrl() + path,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    responseType
            );

            T body = response.getBody();
            if (body == null) {
                log.warn("AI {} 응답 본문이 비었습니다", path);
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
                        "(본문 형식 또는 필수 헤더 누락). path={}, body={}", path, e.getResponseBodyAsString());
            } else {
                log.warn("AI {} 호출 실패: status={}, body={}",
                        path, e.getStatusCode(), e.getResponseBodyAsString());
            }
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        } catch (Exception e) {
            log.warn("AI {} 호출 중 예외", path, e);
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
    }
}
