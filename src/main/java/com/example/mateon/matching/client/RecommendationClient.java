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
 * 별도 FastAPI AI 서버의 추천 엔드포인트 두 개를 호출한다.
 *
 * <ul>
 *   <li>POST /recommendations/user-to-team — 유저가 팀을 찾는다 (지원)</li>
 *   <li>POST /recommendations/team-to-user — 팀이 유저를 찾는다 (역제안)</li>
 * </ul>
 *
 * <p>두 방향을 한 빈에 둔 이유: 엔드포인트만 다르고 요청/응답 스키마와 인증 헤더, 실패 처리
 * 규약이 전부 같다. 클라이언트를 복사하면 401/422 진단 로그가 두 벌이 되고 한쪽만 고쳐지는
 * 사고가 난다.
 *
 * <p>AI 서버는 stateless — 점수만 계산해 돌려주고 저장하지 않는다. 후보를 고르고(모집 중 여부,
 * 본인 팀 제외 등) 결과를 저장하는 건 호출자 몫이다.
 *
 * <p>예외는 IntentExtractionClient 와 같은 규약으로 던진다.
 */
@Slf4j
@Component
public class RecommendationClient {

    private static final String USER_TO_TEAM_PATH = "/recommendations/user-to-team";
    private static final String TEAM_TO_USER_PATH = "/recommendations/team-to-user";

    /** AI 서버가 요구하는 내부 인증 헤더. 값이 없거나 틀리면 AI 가 401 로 거절한다. */
    private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

    private final RestTemplate restTemplate;
    private final AiServerProperties properties;

    // @RequiredArgsConstructor 는 @Qualifier 를 못 붙이므로 생성자를 직접 작성한다.
    // 기본 restTemplate 빈(타임아웃 없음)이 아니라 AI 전용 빈을 주입받아야 한다.
    public RecommendationClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                                AiServerProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /** 유저 한 명에게 맞는 팀들을 점수화한다. */
    public RecommendationResponse userToTeam(UserToTeamRecommendationRequest request) {
        return call(USER_TO_TEAM_PATH, request);
    }

    /** 팀 하나에 맞는 유저들을 점수화한다 (역제안). */
    public RecommendationResponse teamToUser(TeamToUserRecommendationRequest request) {
        return call(TEAM_TO_USER_PATH, request);
    }

    private RecommendationResponse call(String path, Object request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(INTERNAL_SECRET_HEADER, properties.getInternalSecret());

            ResponseEntity<RecommendationResponse> response = restTemplate.exchange(
                    properties.getBaseUrl() + path,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    RecommendationResponse.class
            );

            RecommendationResponse body = response.getBody();
            if (body == null || body.getRecommendations() == null) {
                log.warn("AI {} 응답이 비었거나 recommendations 없음: {}", path, body);
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
