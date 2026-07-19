package com.example.mateon.teams.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내가 이 팀에서 평가해야 할 대상 목록.
 *
 * <p>여기에 다른 사람이 남긴 평가는 절대 담기지 않는다 — alreadyReviewed 는 '내가' 냈는지일 뿐이다.
 */
@Getter
@AllArgsConstructor
public class TeamReviewTargetsResponseDTO {

    private Long teamId;
    private String teamTitle;
    private LocalDateTime endedAt;
    /** 이 시각이 지나면 제출이 거부된다. */
    private LocalDateTime reviewDeadline;
    private List<Target> targets;

    @Getter
    @AllArgsConstructor
    public static class Target {
        private Long userId;
        private String name;
        private String major;
        /** 내가 이미 이 사람을 평가했는지. 제출은 1회뿐이다. */
        private boolean alreadyReviewed;
    }
}
