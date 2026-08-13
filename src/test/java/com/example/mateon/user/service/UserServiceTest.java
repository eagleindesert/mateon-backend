package com.example.mateon.user.service;

import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.matching.repository.MatchingIntentSlotRepository;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.dto.MyPageResponseDTO;
import com.example.mateon.user.dto.PasswordChangeRequest;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 유저 서비스에서 <b>컨트롤러 테스트가 못 보는 두 가지</b>만 다룬다.
 *
 * <p>응답 필드와 협업 온도 폴백은 {@code UserProfileControllerTest} 가 이미 전선까지 고정했으므로
 * 여기서 되풀이하지 않는다. 남는 것은 이것들이다.
 *
 * <p><b>하나, 비밀번호 변경의 부수효과.</b> 새 비밀번호를 저장하는 것만으로는 부족하다 —
 * 기존 리프레시 토큰을 지워야 이미 로그인돼 있던 다른 기기가 끊긴다. 비밀번호를 바꾸는 흔한
 * 이유가 "계정이 털린 것 같아서" 인데, 이 삭제가 빠지면 침입자의 세션이 <b>토큰 만료일까지
 * 그대로 살아 있다</b>. 화면에는 "변경되었습니다"가 뜨므로 사용자는 조치가 끝났다고 믿는다.
 * 평문 비교를 하지 않는 것(인코더 경유)도 여기서 확인한다.
 *
 * <p><b>둘, 참여 활동 조회가 N+1 이 아닌 것.</b> 활동은 멤버십마다 찾지 않고 id 를 모아 한 번에
 * 읽는다. 프로필·마이페이지·공개 프로필 세 곳이 이 메서드를 공유하므로, 되돌아오면 활동을 많이
 * 한 유저의 프로필이 눈에 띄게 느려진다. 성능 회귀는 테스트가 잡지 않으면 아무도 못 잡는다.
 *
 * <p>덤으로 <b>팀장으로 만든 팀도 참여 활동에 들어간다</b>는 것을 고정한다 — '승인된 지원서'로
 * 세면 빠지는 값이라, 집계 기준이 {@code team_members} 라는 사실 자체가 계약이다.
 */
class UserServiceTest {

    private static final long USER_ID = 1L;

    private UserRepository userRepository;
    private TeamMemberRepository teamMemberRepository;
    private UserCollaborationScoreRepository collaborationScoreRepository;
    private EventRepository eventRepository;
    private MatchingIntentSlotRepository matchingIntentSlotRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenRepository refreshTokenRepository;
    private UserService service;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        collaborationScoreRepository = mock(UserCollaborationScoreRepository.class);
        eventRepository = mock(EventRepository.class);
        matchingIntentSlotRepository = mock(MatchingIntentSlotRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);

        service = new UserService(userRepository, teamMemberRepository, collaborationScoreRepository,
                eventRepository, matchingIntentSlotRepository, passwordEncoder, refreshTokenRepository);

        user = User.builder()
                .id(USER_ID).name("나").email("me@example.com")
                .password("ENCODED-OLD").school("메이트대").major("컴퓨터공학")
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(collaborationScoreRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(matchingIntentSlotRepository.findByUserId(anyLong())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("비밀번호 변경")
    class ChangePassword {

        @Test
        @DisplayName("기존 리프레시 토큰을 지운다 — 없으면 침입자 세션이 만료일까지 살아 있다")
        void revokesRefreshTokens() {
            givenCurrentPasswordMatches();

            service.changePassword(USER_ID, request("현재비번", "새비밀번호1234", "새비밀번호1234"));

            verify(refreshTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("토큰 폐기는 새 비밀번호를 저장한 뒤에 일어난다")
        void savesBeforeRevoking() {
            givenCurrentPasswordMatches();

            service.changePassword(USER_ID, request("현재비번", "새비밀번호1234", "새비밀번호1234"));

            InOrder order = inOrder(userRepository, refreshTokenRepository);
            order.verify(userRepository).save(user);
            order.verify(refreshTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("현재 비밀번호는 인코더로 대조하고, 새 비밀번호는 인코딩해서 저장한다")
        void usesEncoderBothWays() {
            givenCurrentPasswordMatches();

            service.changePassword(USER_ID, request("현재비번", "새비밀번호1234", "새비밀번호1234"));

            // 평문 비교가 아니다 — 저장된 값과 직접 equals 하면 절대 맞지 않는다.
            verify(passwordEncoder).matches("현재비번", "ENCODED-OLD");
            verify(passwordEncoder).encode("새비밀번호1234");
            assertThat(user.getPassword()).isEqualTo("ENCODED-새비밀번호1234");
        }

        @Test
        @DisplayName("확인란이 다르면 조회도 인코더 호출도 없이 막힌다")
        void confirmMismatchShortCircuits() {
            assertThatThrownBy(() -> service.changePassword(USER_ID,
                    request("현재비번", "새비밀번호1234", "오타비밀번호99")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_MISMATCH);

            verify(userRepository, never()).findById(anyLong());
            verify(passwordEncoder, never()).matches(anyString(), anyString());
            verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
        }

        /**
         * 확인란 불일치와 현재 비밀번호 오류가 <b>같은 에러 코드</b>다. 프론트는 둘을 구분해
         * 안내할 수 없다. 지금 나가는 그대로를 고정한다.
         */
        @Test
        @DisplayName("현재 비밀번호가 틀려도 같은 PASSWORD_MISMATCH 다 (두 실패를 구분할 수 없다)")
        void wrongCurrentPasswordSameCode() {
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.changePassword(USER_ID,
                    request("틀린비번", "새비밀번호1234", "새비밀번호1234")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_MISMATCH);

            verify(userRepository, never()).save(any());
            verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
        }

        @Test
        @DisplayName("없는 유저는 USER_NOT_FOUND 다")
        void missingUser() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changePassword(USER_ID,
                    request("현재비번", "새비밀번호1234", "새비밀번호1234")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        private void givenCurrentPasswordMatches() {
            when(passwordEncoder.matches("현재비번", "ENCODED-OLD")).thenReturn(true);
            when(passwordEncoder.encode(anyString()))
                    .thenAnswer(invocation -> "ENCODED-" + invocation.getArgument(0));
        }
    }

    @Nested
    @DisplayName("참여 활동 집계 — 세 화면이 공유하는 경로다")
    class ParticipatedActivities {

        @Test
        @DisplayName("활동은 멤버십마다 찾지 않고 한 번에 모아 읽는다 (N+1 방지)")
        void batchesEventLookup() {
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(USER_ID)).thenReturn(List.of(
                    membership(team(10L, "팀A", 100L), TeamMemberRole.MEMBER),
                    membership(team(11L, "팀B", 101L), TeamMemberRole.MEMBER),
                    membership(team(12L, "팀C", 102L), TeamMemberRole.LEADER)));
            when(eventRepository.findAllById(any())).thenReturn(List.of(
                    event(100L, Event.Category.CONTEST),
                    event(101L, Event.Category.SCHOOL),
                    event(102L, Event.Category.EXTERNAL)));

            service.getMyPage(USER_ID);

            verify(eventRepository, times(1)).findAllById(any());
            verify(eventRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("팀장으로 만든 팀도 참여 활동에 들어간다 ('승인된 지원서'로 세면 빠진다)")
        void includesTeamsILead() {
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(USER_ID)).thenReturn(List.of(
                    membership(team(10L, "내가 만든 팀", 100L), TeamMemberRole.LEADER),
                    membership(team(11L, "지원해서 들어간 팀", 101L), TeamMemberRole.MEMBER)));
            when(eventRepository.findAllById(any())).thenReturn(List.of(
                    event(100L, Event.Category.CONTEST), event(101L, Event.Category.SCHOOL)));

            assertThat(service.getMyPage(USER_ID).getParticipatedActivities())
                    .extracting(MyPageResponseDTO.ActivitySummaryDTO::getTitle)
                    .containsExactly("내가 만든 팀", "지원해서 들어간 팀");
        }

        @Test
        @DisplayName("연결 활동이 없는 자율 팀은 조회조차 하지 않고 카테고리가 '기타' 다")
        void standaloneTeamSkipsLookup() {
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(USER_ID))
                    .thenReturn(List.of(membership(team(10L, "자율 팀", null), TeamMemberRole.LEADER)));

            assertThat(service.getMyPage(USER_ID).getParticipatedActivities())
                    .singleElement()
                    .extracting(MyPageResponseDTO.ActivitySummaryDTO::getCategory)
                    .isEqualTo("기타");

            // eventIds 가 비면 리포지토리를 아예 부르지 않는다.
            verify(eventRepository, never()).findAllById(any());
        }

        @Test
        @DisplayName("활동 행이 사라진 팀도 목록에서 빠지지 않고 '기타' 로 표시된다")
        void missingEventFallsBackToEtc() {
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(USER_ID))
                    .thenReturn(List.of(membership(team(10L, "팀A", 100L), TeamMemberRole.MEMBER)));
            when(eventRepository.findAllById(any())).thenReturn(List.of());

            assertThat(service.getMyPage(USER_ID).getParticipatedActivities())
                    .singleElement()
                    .extracting(MyPageResponseDTO.ActivitySummaryDTO::getCategory)
                    .isEqualTo("기타");
        }

        @Test
        @DisplayName("탈퇴한 팀은 집계에서 빠진다 (leftAt 이 없는 행만 조회한다)")
        void onlyActiveMemberships() {
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(USER_ID)).thenReturn(List.of());

            assertThat(service.getMyPage(USER_ID).getParticipatedActivities()).isEmpty();

            verify(teamMemberRepository).findByUserIdAndLeftAtIsNull(USER_ID);
        }

        @Test
        @DisplayName("내 프로필과 마이페이지가 같은 집계를 쓴다 (두 화면의 숫자가 어긋날 수 없다)")
        void profileAndMyPageAgree() {
            when(teamMemberRepository.findByUserIdAndLeftAtIsNull(USER_ID))
                    .thenReturn(List.of(membership(team(10L, "팀A", null), TeamMemberRole.MEMBER)));

            assertThat(service.getMyProfile(USER_ID).getParticipatedActivities()).hasSize(1);
            assertThat(service.getMyPage(USER_ID).getParticipatedActivities()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("조회 경로의 USER_NOT_FOUND")
    class MissingUser {

        @Test
        @DisplayName("내 프로필·마이페이지·공개 프로필 모두 없는 유저에 USER_NOT_FOUND 다 (404)")
        void allThreePaths() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMyProfile(99L))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
            assertThatThrownBy(() -> service.getMyPage(99L))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
            assertThatThrownBy(() -> service.getPublicProfile(99L, USER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("공개 프로필은 대상이 없으면 슬롯을 조회하지 않는다")
        void skipsSlotLookupForMissingUser() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPublicProfile(99L, USER_ID))
                    .isInstanceOf(MateonException.class);

            verify(matchingIntentSlotRepository, never()).findByUserId(anyLong());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private TeamMember membership(Team team, TeamMemberRole role) {
        return TeamMember.of(team, user, role);
    }

    private Team team(long id, String title, Long eventId) {
        Team team = new Team();
        team.setId(id);
        team.setTitle(title);
        team.setEventId(eventId);
        team.setLeaderUserId(USER_ID);
        return team;
    }

    private Event event(long id, Event.Category category) {
        Event event = new Event();
        event.setId(id);
        event.setTitle("활동 " + id);
        event.setCategory(category);
        return event;
    }

    private PasswordChangeRequest request(String current, String next, String confirm) {
        PasswordChangeRequest dto = new PasswordChangeRequest();
        dto.setCurrentPassword(current);
        dto.setNewPassword(next);
        dto.setNewPasswordConfirm(confirm);
        return dto;
    }
}
