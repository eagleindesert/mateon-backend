package com.example.mateon.matching.dto.response;

import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.user.domain.User;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 역제안 추천으로 나온 유저 1명. 점수 내림차순으로 배열에 담겨 나간다.
 *
 * <p>팀장이 "이 사람에게 제안을 보낼까"를 판단할 최소한만 담는다. <b>연락처 성격의 값
 * (email, schoolEmail, providerId)은 절대 넣지 않는다</b> — 아직 아무 관계도 없는 유저의
 * 목록이고, 제안을 보내지 않고도 명단을 긁어갈 수 있게 되기 때문이다. 연락은 제안이 수락된
 * 뒤이거나 기존 DM(POST /api/chat/rooms/dm 의 targetUserId 가 곧 이 userId)으로 한다.
 *
 * <p>desiredRoles/skills 등은 slot(AI 정규화 값)에서 온다 — 팀 쪽 recruiting_roles 와 같은
 * 어휘라 화면에서 나란히 비교된다.
 */
@Getter
public class UserRecommendationResponseDTO {

    private final Long userId;
    private final String name;
    private final String school;
    private final String major;
    private final String grade;
    /** 한 줄 소개. */
    private final String tagline;

    /** AI 가 정규화한 희망 역할 코드 (예: "BE"). */
    private final List<String> desiredRoles;
    private final List<String> skills;
    private final String experienceLevel;
    private final String activityStyle;

    /** 협업 온도. 평가 표본이 부족하면 null(비공개) — 0 도가 아니다. */
    private final BigDecimal collaborationTemperature;

    /** AI 가 매긴 적합도 점수. 정렬 기준이자 화면 표시용. */
    private final double score;

    /** 추천 근거 문구 (예: "BE 역할을 희망하고 있어요"). AI 가 만든 문장을 그대로 내려준다. */
    private final String label;

    public UserRecommendationResponseDTO(User user, MatchingIntentSlot slot,
                                         BigDecimal collaborationTemperature,
                                         double score, String label) {
        this.userId = user.getId();
        this.name = user.getName();
        this.school = user.getSchool();
        this.major = user.getMajor();
        this.grade = user.getGrade();
        this.tagline = user.getTagline();
        this.desiredRoles = slot.getDesiredRoles();
        this.skills = slot.getSkills();
        this.experienceLevel = slot.getExperienceLevel();
        this.activityStyle = slot.getActivityStyle();
        this.collaborationTemperature = collaborationTemperature;
        this.score = score;
        this.label = label;
    }
}
