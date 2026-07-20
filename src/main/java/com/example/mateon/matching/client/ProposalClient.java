package com.example.mateon.matching.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 최종 제안 조립 — 선택된 한 쌍에 맞춘 지원/제안 문구 초안을 AI 에게 받아 온다.
 *
 * <ul>
 *   <li>POST /proposals/user-to-team — 유저가 팀에 보낼 지원 문구</li>
 *   <li>POST /proposals/team-to-user — 팀이 유저에게 보낼 제안 문구</li>
 * </ul>
 *
 * <p>{@link RecommendationClient} 와 나눈 이유는 경로 계열이 다르기 때문이다(/recommendations
 * vs /proposals). 인증 헤더와 실패 처리는 {@link AiCallTemplate} 을 공유하므로 복사되는 코드는
 * 없다.
 *
 * <p>여기서 받은 문구는 <b>저장하지 않는다</b> — 사용자가 화면에서 고친 뒤 기존 발송 API
 * (POST /api/teams/{teamId}/apply · /offers)로 보내며, 그때 생기는 id 가 명세의 proposal_id 다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposalClient {

    private static final String USER_TO_TEAM_PATH = "/proposals/user-to-team";
    private static final String TEAM_TO_USER_PATH = "/proposals/team-to-user";

    private final AiCallTemplate ai;

    /** 유저 → 팀 방향의 지원 문구 초안. */
    public ProposalResponse userToTeam(ProposalAssemblyRequest request) {
        return assemble(USER_TO_TEAM_PATH, request);
    }

    /** 팀 → 유저 방향의 제안 문구 초안 (역제안). */
    public ProposalResponse teamToUser(ProposalAssemblyRequest request) {
        return assemble(TEAM_TO_USER_PATH, request);
    }

    /**
     * 두 방향이 공유하는 본체. 스키마가 같아 경로만 다르다.
     *
     * <p>빈 초안을 거르는 게 여기 있는 유일한 검증이다 — 화면에 빈 입력창을 띄우고 "AI 가
     * 작성했습니다"라고 말하는 것보다 실패가 낫다 ({@code RecommendationClient.reason} 이 빈
     * 문자열을 거부하는 것과 같은 규약).
     */
    private ProposalResponse assemble(String path, ProposalAssemblyRequest request) {
        ProposalResponse body = ai.post(path, request, ProposalResponse.class);

        if (isBlank(body.getSummary()) || isBlank(body.getMessage())) {
            log.warn("AI {} 가 빈 초안을 반환했습니다. summary={}, message={}",
                    path, body.getSummary(), body.getMessage());
            throw new MateonException(ErrorCode.AI_SERVER_ERROR);
        }
        return body;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
