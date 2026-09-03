package com.example.mateon.user.controller;

import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.repository.MatchingIntentSlotRepository;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.user.repository.UserRepository;
import com.example.mateon.user.service.ProfileImageService;
import com.example.mateon.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유저 프로필 3종(GET /api/users/{userId}, GET·PUT /api/users/me)이 밖으로 내보이는 계약을 고정한다.
 *
 * <p>
 * 서비스를 목으로 두지 않고 리포지토리만 목으로 둔 채 진짜 {@link UserService} 를 조립한다
 * (EventQueryBehaviorTest 와 같은 방식). 이 API 에서 정작 검증해야 할 건 "슬롯이 없을 때",
 * "평가가 모자랄 때" 같은 조립 규칙인데, 서비스를 목으로 두면 그게 전부 사라진다.
 *
 * <p>
 * 가장 중요한 테스트는 {@code 이메일이_응답에_없다} 다. 이 엔드포인트는 로그인만 하면 누구나
 * 부를 수 있어서, 여기에 이메일이 실리는 순간 userId 를 훑는 것만으로 전교생 연락처가 수집된다.
 */
class UserProfileControllerTest {

    private static final long VIEWER_ID = 7L;
    private static final long TARGET_ID = 42L;

    private UserRepository userRepository;
    private TeamMemberRepository teamMemberRepository;
    private UserCollaborationScoreRepository collaborationScoreRepository;
    private EventRepository eventRepository;
    private MatchingIntentSlotRepository slotRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenRepository refreshTokenRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        collaborationScoreRepository = mock(UserCollaborationScoreRepository.class);
        eventRepository = mock(EventRepository.class);
        slotRepository = mock(MatchingIntentSlotRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);

        UserService userService = new UserService(
          userRepository,
          teamMemberRepository,
          collaborationScoreRepository,
          eventRepository,
          slotRepository,
          passwordEncoder,
          refreshTokenRepository);

        mockMvc = MockMvcBuilders
          .standaloneSetup(new UserController(userService, mock(ProfileImageService.class)))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

        // 기본값: 대상 유저는 존재하고, 슬롯·온도·활동은 아직 없다 (가입 직후 상태).
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser()));
        when(slotRepository.findByUserId(TARGET_ID)).thenReturn(Optional.empty());
        when(collaborationScoreRepository.findById(TARGET_ID)).thenReturn(Optional.empty());
        when(teamMemberRepository.findByUserIdAndLeftAtIsNull(TARGET_ID)).thenReturn(List.of());
    }

    @Nested
    @DisplayName("GET /api/users/{userId}")
    class PublicProfile {

        @Test
        @DisplayName("공개 프로필 필드가 응답에 실린다")
        void returnsPublicFields() throws Exception {
            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data.userId").value(TARGET_ID))
              .andExpect(jsonPath("$.data.name").value("김루미"))
              .andExpect(jsonPath("$.data.school").value("단국대학교"))
              .andExpect(jsonPath("$.data.campus").value("죽전"))
              .andExpect(jsonPath("$.data.college").value("SW융합대학"))
              .andExpect(jsonPath("$.data.major").value("소프트웨어학과"))
              .andExpect(jsonPath("$.data.grade").value("3학년"))
              .andExpect(jsonPath("$.data.tagline").value("백엔드 하고 싶어요"))
              // 포트폴리오는 본인이 보여주려고 쓴 소개글이라 연락처와 달리 남에게도 보인다.
              .andExpect(jsonPath("$.data.portfolio")
                .value("사이드 프로젝트 3개를 했어요.\n- 마테온: Spring Boot 백엔드"))
              .andExpect(jsonPath("$.data.schoolVerified").value(true))
              .andExpect(jsonPath("$.data.interestJobPrimary").value("백엔드 개발자"));
        }

        @Test
        @DisplayName("이메일이 응답에 없다 - 로그인만 하면 누구나 부를 수 있는 API 이므로")
        void doesNotExposeEmail() throws Exception {
            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.email").doesNotExist())
              .andExpect(jsonPath("$.data.schoolEmail").doesNotExist())
              .andExpect(jsonPath("$.data.password").doesNotExist())
              .andExpect(jsonPath("$.data.provider").doesNotExist())
              .andExpect(jsonPath("$.data.providerId").doesNotExist());
        }

        @Test
        @DisplayName("의도 추출을 안 한 유저는 역할/스킬이 null 이 아니라 빈 배열이다")
        void slotlessUserGetsEmptyListsNotNull() throws Exception {
            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.desiredRoles").isArray())
              .andExpect(jsonPath("$.data.desiredRoles").isEmpty())
              .andExpect(jsonPath("$.data.skills").isArray())
              .andExpect(jsonPath("$.data.skills").isEmpty())
              .andExpect(jsonPath("$.data.experienceLevel").doesNotExist())
              .andExpect(jsonPath("$.data.activityStyle").doesNotExist());
        }

        @Test
        @DisplayName("슬롯이 있으면 추천 카드와 같은 어휘로 역할/스킬이 나간다")
        void exposesSlotValues() throws Exception {
            MatchingIntentSlot slot = new MatchingIntentSlot(targetUser());
            slot.update(null, List.of("BE"), List.of("Spring", "PostgreSQL"), List.of("교육"),
              "포트폴리오 만들기", "주 2회 온라인", "beginner", "임베딩 원문");
            when(slotRepository.findByUserId(TARGET_ID)).thenReturn(Optional.of(slot));

            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.desiredRoles[0]").value("BE"))
              .andExpect(jsonPath("$.data.skills[0]").value("Spring"))
              .andExpect(jsonPath("$.data.skills[1]").value("PostgreSQL"))
              .andExpect(jsonPath("$.data.experienceLevel").value("beginner"))
              .andExpect(jsonPath("$.data.activityStyle").value("주 2회 온라인"));
        }

        @Test
        @DisplayName("평가가 1건이어도 협업 온도가 실린다 - 표본이 적다는 이유로 감추지 않는다")
        void temperatureIsExposedWithASingleReview() throws Exception {
            UserCollaborationScore score = UserCollaborationScore.init(TARGET_ID);
            score.addRating(5);
            when(collaborationScoreRepository.findById(TARGET_ID)).thenReturn(Optional.of(score));

            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.collaborationTemperature").value(37.0))
              .andExpect(jsonPath("$.data.collaborationReviewCount").value(1));
        }

        @Test
        @DisplayName("평가를 한 번도 안 받은 유저는 기준점 36.5 다 - 집계 행이 없어도 값이 나간다")
        void neverReviewedUserGetsBaseTemperature() throws Exception {
            when(collaborationScoreRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.collaborationTemperature").value(36.5))
              .andExpect(jsonPath("$.data.collaborationReviewCount").value(0));
        }

        @Test
        @DisplayName("평가가 충분하면 협업 온도가 공개된다")
        void temperatureIsExposedWhenEnoughReviews() throws Exception {
            UserCollaborationScore score = UserCollaborationScore.init(TARGET_ID);
            score.addRating(5);
            score.addRating(5);
            when(collaborationScoreRepository.findById(TARGET_ID)).thenReturn(Optional.of(score));

            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.collaborationTemperature").isNumber())
              .andExpect(jsonPath("$.data.collaborationReviewCount").value(2));
        }

        @Test
        @DisplayName("참여 활동에 연결된 활동의 카테고리가 한글로 실린다")
        void exposesParticipatedActivities() throws Exception {
            Team team = team(11L, "교내 해커톤 팀", 99L);
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(TARGET_ID))
              .thenReturn(List.of(TeamMember.builder().team(team).build()));
            when(eventRepository.findAllById(any()))
              .thenReturn(List.of(event(99L, Event.Category.SCHOOL)));

            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.participatedActivities[0].id").value(11))
              .andExpect(jsonPath("$.data.participatedActivities[0].title").value("교내 해커톤 팀"))
              .andExpect(jsonPath("$.data.participatedActivities[0].category").value("교내"));
        }

        @Test
        @DisplayName("연결된 활동이 없는 팀은 카테고리가 '기타' 다")
        void teamWithoutEventFallsBackToEtc() throws Exception {
            Team team = team(12L, "자체 스터디", null);
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(TARGET_ID))
              .thenReturn(List.of(TeamMember.builder().team(team).build()));

            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.participatedActivities[0].category").value("기타"));
        }

        @Test
        @DisplayName("남의 프로필이면 isMe=false")
        void isMeIsFalseForOthers() throws Exception {
            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(VIEWER_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.isMe").value(false));
        }

        @Test
        @DisplayName("자기 id 로 조회하면 isMe=true")
        void isMeIsTrueForSelf() throws Exception {
            mockMvc.perform(get("/api/users/{userId}", TARGET_ID).principal(auth(TARGET_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.isMe").value(true));
        }

        @Test
        @DisplayName("없는 userId 는 404 다 (400 이 아니다 - 요청이 틀린 게 아니라 그 사람이 없는 것이다)")
        void unknownUserIsNotFound() throws Exception {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/users/{userId}", 999L).principal(auth(VIEWER_ID)))
              .andExpect(status().isNotFound())
              .andExpect(jsonPath("$.success").value(false))
              .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."));
        }
    }

    /**
     * 마이페이지 화면이 실제로 읽는 엔드포인트다. 협업 온도·참여 활동이 여기서 빠지면 화면에서
     * 사라진다 — 같은 값을 주는 {@code /mypage} 는 프론트가 쓰지 않는 중복 경로라 대체가 안 된다.
     */
    @Nested
    @DisplayName("GET /api/users/me")
    class MyProfile {

        @BeforeEach
        void stubSelf() {
            when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser()));
        }

        @Test
        @DisplayName("평가가 충분하면 협업 온도가 실린다")
        void exposesTemperature() throws Exception {
            UserCollaborationScore score = UserCollaborationScore.init(TARGET_ID);
            score.addRating(5);
            score.addRating(5);
            when(collaborationScoreRepository.findById(TARGET_ID)).thenReturn(Optional.of(score));

            mockMvc.perform(get("/api/users/me").principal(auth(TARGET_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.collaborationTemperature").isNumber())
              .andExpect(jsonPath("$.data.collaborationReviewCount").value(2));
        }

        @Test
        @DisplayName("평가가 1건이어도 온도와 건수가 함께 실린다 - 표본이 적다는 이유로 감추지 않는다")
        void temperatureIsExposedWithASingleReview() throws Exception {
            UserCollaborationScore score = UserCollaborationScore.init(TARGET_ID);
            score.addRating(5);
            when(collaborationScoreRepository.findById(TARGET_ID)).thenReturn(Optional.of(score));

            mockMvc.perform(get("/api/users/me").principal(auth(TARGET_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.collaborationTemperature").value(37.0))
              .andExpect(jsonPath("$.data.collaborationReviewCount").value(1));
        }

        @Test
        @DisplayName("평가를 한 번도 안 받았으면 기준점 36.5 에 건수 0 이다 - 온도를 싣지 않는 응답과 구분된다")
        void neverReviewedUserGetsBaseTemperature() throws Exception {
            when(collaborationScoreRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/users/me").principal(auth(TARGET_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.collaborationTemperature").value(36.5))
              .andExpect(jsonPath("$.data.collaborationReviewCount").value(0));
        }

        @Test
        @DisplayName("참여 활동이 /mypage 와 같은 키 구성으로 실린다")
        void exposesParticipatedActivities() throws Exception {
            Team team = team(11L, "교내 해커톤 팀", 99L);
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(TARGET_ID))
              .thenReturn(List.of(TeamMember.builder().team(team).build()));
            when(eventRepository.findAllById(any()))
              .thenReturn(List.of(event(99L, Event.Category.SCHOOL)));

            mockMvc.perform(get("/api/users/me").principal(auth(TARGET_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.participatedActivities[0].id").value(11))
              .andExpect(jsonPath("$.data.participatedActivities[0].title").value("교내 해커톤 팀"))
              .andExpect(jsonPath("$.data.participatedActivities[0].category").value("교내"));
        }

        @Test
        @DisplayName("참여한 팀이 없으면 null 이 아니라 빈 배열이다")
        void emptyActivitiesIsArrayNotNull() throws Exception {
            mockMvc.perform(get("/api/users/me").principal(auth(TARGET_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.participatedActivities").isArray())
              .andExpect(jsonPath("$.data.participatedActivities").isEmpty());
        }

        @Test
        @DisplayName("기존 키는 그대로 남아 있다 - 필드는 더하기만 했다")
        void keepsExistingSchema() throws Exception {
            mockMvc.perform(get("/api/users/me").principal(auth(TARGET_ID)))
              .andExpect(status().isOk())
              // 내 프로필이므로 이메일 2종은 계속 실린다 (남의 프로필과 다른 점이다).
              .andExpect(jsonPath("$.data.email").value("rumi@dankook.ac.kr"))
              .andExpect(jsonPath("$.data.schoolEmail").value("rumi@dankook.ac.kr"))
              // 남의 프로필은 userId 인데 이쪽은 id 다 — 통일하려다 프론트를 깨뜨리지 않도록 고정한다.
              .andExpect(jsonPath("$.data.id").value(TARGET_ID))
              .andExpect(jsonPath("$.data.name").value("김루미"))
              .andExpect(jsonPath("$.data.school").value("단국대학교"))
              .andExpect(jsonPath("$.data.campus").value("죽전"))
              .andExpect(jsonPath("$.data.college").value("SW융합대학"))
              .andExpect(jsonPath("$.data.major").value("소프트웨어학과"))
              .andExpect(jsonPath("$.data.grade").value("3학년"))
              .andExpect(jsonPath("$.data.tagline").value("백엔드 하고 싶어요"))
              .andExpect(jsonPath("$.data.portfolio")
                .value("사이드 프로젝트 3개를 했어요.\n- 마테온: Spring Boot 백엔드"))
              .andExpect(jsonPath("$.data.schoolVerified").value(true))
              .andExpect(jsonPath("$.data.interestJobPrimary").value("백엔드 개발자"));
        }
    }

    /**
     * 프로필을 고치는 유일한 경로다. 여기서 검증할 건 "보낸 필드만 바뀐다"는 부분 수정 규약인데,
     * 이건 {@link User#update} 의 null 가드에 전부 들어 있어서 컨트롤러부터 엔티티까지 이어 봐야
     * 확인된다.
     */
    @Nested
    @DisplayName("PUT /api/users/me")
    class UpdateMyProfile {

        @BeforeEach
        void stubSelf() {
            // 서비스가 같은 인스턴스를 고쳐서 응답까지 만들므로, 저장 결과를 따로 흉내 낼 필요가 없다.
            User self = targetUser();
            when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(self));
            when(userRepository.save(any(User.class))).thenReturn(self);
        }

        @Test
        @DisplayName("포트폴리오를 보내면 응답에 새 값이 실린다")
        void updatesPortfolio() throws Exception {
            mockMvc.perform(put("/api/users/me")
              .principal(auth(TARGET_ID))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"portfolio\":\"새로 쓴 포트폴리오\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data.portfolio").value("새로 쓴 포트폴리오"));
        }

        @Test
        @DisplayName("포트폴리오를 빼고 보내면 기존 값이 지워지지 않는다")
        void omittedPortfolioIsLeftAlone() throws Exception {
            mockMvc.perform(put("/api/users/me")
              .principal(auth(TARGET_ID))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"tagline\":\"프론트도 해보고 싶어요\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.tagline").value("프론트도 해보고 싶어요"))
              .andExpect(jsonPath("$.data.portfolio")
                .value("사이드 프로젝트 3개를 했어요.\n- 마테온: Spring Boot 백엔드"));
        }

        @Test
        @DisplayName("포트폴리오가 5000자를 넘으면 400 - 컬럼이 text 라 DB 가 대신 막아주지 않는다")
        void rejectsOversizedPortfolio() throws Exception {
            String tooLong = "가".repeat(5001);

            mockMvc.perform(put("/api/users/me")
              .principal(auth(TARGET_ID))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"portfolio\":\"" + tooLong + "\"}"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.success").value(false))
              .andExpect(jsonPath("$.data.portfolio").value("포트폴리오는 5000자 이하여야 합니다."));
        }
    }

    /**
     * 폐기 예정이지만 응답은 /me 와 같아야 한다. 프론트가 아직 부를 수 있어서 필드가 빠지면
     * 마이페이지 화면에서 협업 온도·참여 활동이 사라진다.
     */
    @Nested
    @DisplayName("GET /api/users/mypage")
    class MyPage {

        @BeforeEach
        void stubSelf() {
            when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser()));
        }

        @Test
        @DisplayName("이름·협업 온도·참여 활동이 실린다")
        void returnsSummaryFields() throws Exception {
            mockMvc.perform(get("/api/users/mypage").principal(auth(TARGET_ID)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data.name").value("김루미"))
              .andExpect(jsonPath("$.data.school").value("단국대학교"))
              .andExpect(jsonPath("$.data.collaborationTemperature").value(36.5))
              .andExpect(jsonPath("$.data.collaborationReviewCount").value(0))
              .andExpect(jsonPath("$.data.participatedActivities").isArray());
        }
    }

    @Nested
    @DisplayName("POST /api/users/password/change")
    class ChangePassword {

        @BeforeEach
        void stubSelf() {
            User self = targetUser();
            when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(self));
            when(userRepository.save(any(User.class))).thenReturn(self);
            when(passwordEncoder.matches("old-password", "encoded-secret")).thenReturn(true);
            when(passwordEncoder.encode("new-password1")).thenReturn("encoded-new");
        }

        @Test
        @DisplayName("성공하면 다시 로그인하라는 안내가 나간다")
        void changesPassword() throws Exception {
            mockMvc.perform(post("/api/users/password/change")
              .principal(auth(TARGET_ID))
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                {"currentPassword":"old-password",
                 "newPassword":"new-password1",
                 "newPasswordConfirm":"new-password1"}
                """))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.message").value("비밀번호가 변경되었습니다. 다시 로그인해주세요."))
              .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    // --- 헬퍼 ---
    private Authentication auth(long userId) {
        return new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, List.of());
    }

    private User targetUser() {
        return User.builder()
          .id(TARGET_ID)
          .email("rumi@dankook.ac.kr")
          .schoolEmail("rumi@dankook.ac.kr")
          .password("encoded-secret")
          .providerId("kakao-1234")
          .schoolVerified(true)
          .name("김루미")
          .school("단국대학교")
          .campus("죽전")
          .college("SW융합대학")
          .major("소프트웨어학과")
          .grade("3학년")
          .interestJobPrimary("백엔드 개발자")
          .tagline("백엔드 하고 싶어요")
          .portfolio("사이드 프로젝트 3개를 했어요.\n- 마테온: Spring Boot 백엔드")
          .build();
    }

    private Team team(Long id, String title, Long eventId) {
        Team team = new Team();
        team.setId(id);
        team.setTitle(title);
        team.setEventId(eventId);
        return team;
    }

    private Event event(Long id, Event.Category category) {
        Event event = new Event();
        event.setId(id);
        event.setCategory(category);
        return event;
    }
}
