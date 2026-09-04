package com.example.mateon.common.config;

import com.example.mateon.auth.jwt.JwtTokenProvider;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityConfig} 의 인가 규칙을 실제 필터 체인으로 확인한다.
 *
 * <p>
 * 컨트롤러 테스트는 전부 standalone MockMvc 라 필터 체인을 타지 않는다. 그래서 어떤 경로가
 * 비로그인에 열려 있고 어떤 경로가 닫혀 있는지는 이 클래스 말고는 아무도 보지 않는다.
 * SecurityConfig 주석에 적힌 두 사고가 정확히 여기서 잡혀야 하는 회귀다 — 팀 목록에
 * permitAll 이 빠져 실제로는 403 이었던 것, 그리고 first-match-wins 라 {@code POST /api/events}
 * 가 {@code /api/events/**} permitAll 아래로 내려가면 비인증 등록이 열리는 것.
 *
 * <p>
 * 서비스 동작은 보지 않는다. 열린 경로는 "403 이 아니다"만 보되, 현재 나가는 상태코드를
 * 그대로 적어 두어 어느 층에서 멈췄는지가 드러나게 한다. 닫힌 경로는 본문이 무엇이든
 * 검증 전에 403 으로 끊겨야 한다 — 400 이 나오면 필터를 통과해 컨트롤러까지 간 것이다.
 *
 * <p>
 * 비인증 응답이 401 이 아니라 403 인 이유: 진입점(entry point)을 따로 두지 않아 스프링
 * 시큐리티 기본값이 쓰인다. 프론트가 이미 이 코드로 동작 중이므로 그대로 고정한다.
 */
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtTokenProvider jwtTokenProvider;
    @Autowired
    UserRepository userRepository;

    @Nested
    @DisplayName("비로그인에 열린 경로")
    class OpenToAnonymous {

        @Test
        @DisplayName("헬스체크는 토큰 없이 200 이다")
        void health() throws Exception {
            mockMvc.perform(get("/health")).andExpect(status().isOk());
            mockMvc.perform(get("/")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("팀 목록·상세 GET 은 열려 있다 (permitAll 이 빠지면 403 이 된다)")
        void teamListAndDetail() throws Exception {
            mockMvc.perform(get("/api/teams"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true));

            // 없는 팀이라 400 이지만, 필터에서 끊겼다면 403 이었을 것이다.
            mockMvc.perform(get("/api/teams/999999"))
              .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("활동 목록·검색 GET 은 열려 있다")
        void eventQueries() throws Exception {
            mockMvc.perform(get("/api/events")).andExpect(status().isOk());
            mockMvc.perform(get("/api/events/search")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("로그인 전 인증 API 는 열려 있다 (본문이 비어 400 이지만 403 은 아니다)")
        void authApisBeforeLogin() throws Exception {
            mockMvc.perform(post("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
              .andExpect(status().isBadRequest());

            mockMvc.perform(post("/api/auth/email/request")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
              .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("스웨거 문서는 열려 있다")
        void swagger() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("비로그인에 닫힌 경로 — 본문이 무엇이든 검증 전에 403 으로 끊긴다")
    class ClosedToAnonymous {

        @Test
        @DisplayName("활동 등록 POST 는 /api/events/** permitAll 보다 위에 있어 닫혀 있다")
        void eventCreate() throws Exception {
            mockMvc.perform(post("/api/events")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
              .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("포스터 이미지 추출 POST 도 등록의 일부라 닫혀 있다")
        void eventExtractImage() throws Exception {
            mockMvc.perform(multipart("/api/events/extract-image")
              .file(new MockMultipartFile("image", "a.png", "image/png", new byte[]{1})))
              .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("추천 활동은 permitAll 매처 아래 있어도 닫혀 있다")
        void eventRecommended() throws Exception {
            mockMvc.perform(get("/api/events/recommended"))
              .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("팀 GET 의 permitAll 은 한 단계까지만이다 — 지원서·제안·평가는 닫혀 있다")
        void teamSubResourcesStayClosed() throws Exception {
            mockMvc.perform(get("/api/teams/applications/me"))
              .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/teams/offers/me"))
              .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/teams/7/applications"))
              .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/teams/7/reviews/targets"))
              .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("팀 생성·지원은 GET 이 아니라 닫혀 있다")
        void teamWrites() throws Exception {
            mockMvc.perform(post("/api/teams")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
              .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/teams/7/apply")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
              .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("학교 인증은 /api/auth/** permitAll 보다 위에 있어 닫혀 있다")
        void schoolVerification() throws Exception {
            mockMvc.perform(post("/api/auth/school/email/request")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
              .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("사용자·북마크·채팅·알림·매칭·포트폴리오는 전부 닫혀 있다")
        void personalApis() throws Exception {
            mockMvc.perform(get("/api/users/me")).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/bookmarks/events")).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/chat/rooms")).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/notifications")).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/matching/intents/session")).andExpect(status().isForbidden());
            mockMvc.perform(multipart("/api/portfolios/summarize")
              .file(new MockMultipartFile("pdf_file", "a.pdf", "application/pdf", new byte[]{1})))
              .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("깨진 토큰은 없는 것과 같다 — 필터가 무시하고 익명으로 흘려보낸다")
        void garbageTokenIsAnonymous() throws Exception {
            mockMvc.perform(get("/api/users/me")
              .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
              .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("토큰이 있으면 닫힌 경로가 열린다")
    class WithToken {

        @Test
        @DisplayName("Bearer 액세스 토큰으로 내 정보와 내 지원서 목록을 읽는다")
        void bearerTokenOpensPersonalApis() throws Exception {
            User user = userRepository.save(User.builder()
              .email(UUID.randomUUID() + "@test.ac.kr")
              .name("인가 테스트 유저")
              .build());
            String bearer = "Bearer " + jwtTokenProvider.createAccessToken(user.getId());

            mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.name").value("인가 테스트 유저"));

            mockMvc.perform(get("/api/teams/applications/me").header(HttpHeaders.AUTHORIZATION, bearer))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").isArray());
        }

        /**
         * 현재 동작을 그대로 문서화한다. {@code JwtTokenProvider} 의 액세스·리프레시 토큰은
         * 만료 시간만 다르고 구조가 같아(타입 클레임이 없다) 필터가 둘을 구분하지 못한다.
         * 리프레시 토큰은 7일짜리라, 유출되면 액세스 토큰 만료와 무관하게 그 기간 내내 API 를
         * 부를 수 있다. 토큰에 타입 클레임을 넣고 필터가 거르도록 바꾸면 이 단언을 403 으로
         * 뒤집어야 한다.
         */
        @Test
        @DisplayName("리프레시 토큰도 액세스 토큰처럼 API 를 연다 — 타입 클레임이 없어 구분하지 못하는 현재 동작")
        void refreshTokenCurrentlyAuthenticates() throws Exception {
            User user = userRepository.save(User.builder()
              .email(UUID.randomUUID() + "@test.ac.kr")
              .name("리프레시 유저")
              .build());
            String bearer = "Bearer " + jwtTokenProvider.createRefreshToken(user.getId());

            mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer))
              .andExpect(status().isOk());
        }
    }
}
