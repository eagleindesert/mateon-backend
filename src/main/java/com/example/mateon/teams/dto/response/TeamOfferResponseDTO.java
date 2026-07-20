package com.example.mateon.teams.dto.response;

import com.example.mateon.teams.domain.OfferStatus;
import com.example.mateon.teams.domain.TeamOffer;
import com.example.mateon.user.domain.User;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 역제안 1건. 받은 쪽(유저)과 보낸 쪽(팀장)이 같은 DTO 를 쓰고 프론트가 필요한 면만 읽는다.
 *
 * <p>유저 화면에서는 "어느 팀이 나를 왜 원하는가"(team*, aiLabel)가, 팀장 화면에서는
 * "누구에게 보냈고 어떻게 됐나"(targetUser*, status)가 주된 관심사다.
 *
 * <p>이메일 등 연락처 성격의 값은 담지 않는다 — 제안이 수락되기 전에는 아직 아무 관계도 아니다.
 * 대화가 필요하면 DM(POST /api/chat/rooms/dm)의 targetUserId 로 leaderId/targetUserId 를 쓰면 된다.
 */
@Getter
@Builder
public class TeamOfferResponseDTO {

    private Long offerId;

    // ── 제안한 팀 ────────────────────────────────────────────────────────────
    private Long teamId;
    private String teamTitle;
    private String promotionText;
    private List<String> role;
    private List<String> requiredSkills;
    private Integer capacity;
    private Long eventId;
    private Long leaderId;
    private String leaderName;

    // ── 제안받은 사람 ────────────────────────────────────────────────────────
    private Long targetUserId;
    private String targetUserName;
    private String targetUserSchool;
    private String targetUserMajor;

    // ── 제안 내용 ────────────────────────────────────────────────────────────
    private String message;
    /** 제안 시점의 AI 적합도 점수. 추천을 거치지 않고 보낸 제안이면 null. */
    private Double aiScore;
    /** 제안 시점의 AI 추천 근거 문구. 추천을 거치지 않고 보낸 제안이면 null. */
    private String aiLabel;

    private OfferStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;

    /**
     * @param leader 팀장. 팀 엔티티는 leaderUserId 만 들고 있어 이름은 밖에서 조회해 넘긴다.
     *               조회에 실패했으면 null 을 넘겨도 된다 (이름만 비고 나머지는 정상).
     */
    public static TeamOfferResponseDTO from(TeamOffer offer, User leader) {
        User target = offer.getTargetUser();
        return TeamOfferResponseDTO.builder()
                .offerId(offer.getId())
                .teamId(offer.getTeam().getId())
                .teamTitle(offer.getTeam().getTitle())
                .promotionText(offer.getTeam().getPromotionText())
                .role(offer.getTeam().getRole())
                .requiredSkills(offer.getTeam().getRequiredSkills())
                .capacity(offer.getTeam().getCapacity())
                .eventId(offer.getTeam().getEventId())
                .leaderId(offer.getTeam().getLeaderUserId())
                .leaderName(leader != null ? leader.getName() : null)
                .targetUserId(target.getId())
                .targetUserName(target.getName())
                .targetUserSchool(target.getSchool())
                .targetUserMajor(target.getMajor())
                .message(offer.getMessage())
                .aiScore(offer.getAiScore())
                .aiLabel(offer.getAiLabel())
                .status(offer.getStatus())
                .createdAt(offer.getCreatedAt())
                .respondedAt(offer.getRespondedAt())
                .build();
    }
}
