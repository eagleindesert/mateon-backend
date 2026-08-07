package com.example.mateon.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 남이 보는 프로필 1명 (GET /api/users/{userId}).
 *
 * <p><b>{@link UserResponse} 를 재사용하지 않는 이유</b>: 그쪽은 email, schoolEmail 을 담는
 * "내 프로필" 전용 DTO다. 로그인한 사람이면 누구나 이 API 를 부를 수 있으므로, 그대로 쓰면
 * userId 를 1 부터 훑는 것만으로 전교생 이메일이 수집된다.
 *
 * <p>그래서 <b>연락처 성격의 값(email, schoolEmail, providerId)은 절대 넣지 않는다</b> —
 * {@link com.example.mateon.matching.dto.response.UserRecommendationResponseDTO} 가 세워둔
 * 원칙과 같다. 연락은 DM(POST /api/chat/rooms/dm 의 targetUserId 가 곧 이 userId)으로 한다.
 *
 * <p>슬롯 필드(desiredRoles/skills/experienceLevel/activityStyle)의 집합을 추천 카드와
 * 일부러 똑같이 맞췄다. 추천 목록에서 이 프로필로 넘어왔을 때 같은 어휘가 그대로 보여야
 * "아까 그 사람"으로 읽힌다.
 */
@Getter
@Builder
public class UserProfileResponse {

    private final Long userId;
    private final String name;
    private final String school;
    private final String campus;
    private final String college;
    private final String major;
    private final String grade;
    /** 한 줄 소개. */
    private final String tagline;
    /**
     * 프로필 사진 공개 URL. 사진이 없거나 업로드가 아직 안 끝났으면 null.
     *
     * <p>연락처가 아니므로 이 DTO 의 비공개 원칙(위 주석)에 걸리지 않는다 — 사진은 추천 카드에서도
     * 보여야 하는 공개 항목이다.
     */
    private final String profileImageUrl;
    private final boolean schoolVerified;

    private final String interestJobPrimary;
    private final String interestJobSecondary;
    private final String interestJobTertiary;

    /** AI 가 정규화한 희망 역할 코드 (예: "BE"). 슬롯 미작성이면 빈 배열. */
    private final List<String> desiredRoles;
    /** 슬롯 미작성이면 빈 배열. */
    private final List<String> skills;
    /** 슬롯 미작성이면 null. */
    private final String experienceLevel;
    /** 슬롯 미작성이면 null. */
    private final String activityStyle;

    /** 협업 온도. 평가 건수와 무관하게 항상 값이 있고, 0건이면 기준점 36.5 다. */
    private final BigDecimal collaborationTemperature;
    private final int collaborationReviewCount;

    /** 참여했던 활동 이력. */
    private final List<MyPageResponseDTO.ActivitySummaryDTO> participatedActivities;

    /**
     * 조회자 본인의 프로필인지. 프론트가 '프로필 수정' 버튼을 띄울지 판단한다.
     *
     * <p>키 이름을 {@code @JsonProperty} 로 못박은 이유: boolean 의 is- 접두사는 Jackson 이
     * 프로퍼티 이름에서 떼어낼 수 있어 {@code me} 로 나갈 여지가 있고, 이 프로젝트는 Jackson 2 와
     * 3 이 함께 클래스패스에 있다 (Boot 4 는 Jackson 3, jjwt-jackson 이 2 를 끌고 옴). 프론트가
     * 읽는 키를 런타임 조합에 맡길 이유가 없다.
     */
    @JsonProperty("isMe")
    private final boolean isMe;

    /**
     * @param slot  매칭 의도 슬롯. 의도 추출을 아직 안 한 유저는 null 이다 (에러가 아니다).
     * @param score 협업 온도 집계. 평가를 한 번도 안 받았으면 null 이며, 기준점 온도로 내려간다.
     */
    public static UserProfileResponse of(User user,
                                         MatchingIntentSlot slot,
                                         UserCollaborationScore score,
                                         List<MyPageResponseDTO.ActivitySummaryDTO> activities,
                                         boolean isMe) {
        return UserProfileResponse.builder()
                .userId(user.getId())
                .name(user.getName())
                .school(user.getSchool())
                .campus(user.getCampus())
                .college(user.getCollege())
                .major(user.getMajor())
                .grade(user.getGrade())
                .tagline(user.getTagline())
                .profileImageUrl(user.getProfileImageUrl())
                .schoolVerified(user.isSchoolVerified())
                .interestJobPrimary(user.getInterestJobPrimary())
                .interestJobSecondary(user.getInterestJobSecondary())
                .interestJobTertiary(user.getInterestJobTertiary())
                // 슬롯이 없을 때 null 이 아니라 빈 배열을 주는 이유: 프론트가 목록을 그대로
                // map 하기 때문이다. "아직 안 썼다"와 "없다"를 화면에서 구분할 필요도 없다.
                .desiredRoles(slot != null ? nullSafe(slot.getDesiredRoles()) : Collections.emptyList())
                .skills(slot != null ? nullSafe(slot.getSkills()) : Collections.emptyList())
                .experienceLevel(slot != null ? slot.getExperienceLevel() : null)
                .activityStyle(slot != null ? slot.getActivityStyle() : null)
                .collaborationTemperature(score != null
                        ? score.getTemperature()
                        : CollaborationTemperatureCalculator.INITIAL)
                .collaborationReviewCount(score != null ? score.getReviewCount() : 0)
                .participatedActivities(activities)
                .isMe(isMe)
                .build();
    }

    /** 슬롯 컬럼은 TEXT 컨버터라 값이 비어 있으면 null 로 올라온다. */
    private static List<String> nullSafe(List<String> values) {
        return values != null ? values : Collections.emptyList();
    }
}
