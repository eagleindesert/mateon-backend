package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.IntentPrefixKind;
import com.example.mateon.aichat.domain.IntentPrefixLine;
import com.example.mateon.user.domain.User;

import java.util.ArrayList;
import java.util.List;

/**
 * 매칭 의도 추출 요청 앞에 붙일 접두 본문을 만든다.
 *
 * <p>
 * 라벨 문자열은 FastAPI 명세와 한 글자도 같아야 한다. 토글이 꺼져 있거나 본문이 비면
 * 그 kind 는 목록에 넣지 않는다 — 호출부가 없는 kind 의 저장 행을 지운다.
 */
public final class IntentExtractPrefixFactory {

    /**
     * FastAPI 명세의 자기소개서 라벨.
     */
    public static final String PROFILE_LABEL = "[자기소개서]";

    /**
     * FastAPI 명세의 포트폴리오 라벨.
     */
    public static final String PORTFOLIO_LABEL = "[포트폴리오]";

    private IntentExtractPrefixFactory() {
    }

    /**
     * 토글과 본문이 있는 접두만, PROFILE 다음 PORTFOLIO 순.
     */
    public static List<IntentPrefixLine> from(User user) {
        List<IntentPrefixLine> prefixes = new ArrayList<>(2);
        if (user.isMatchIncludeProfile()) {
            String body = profileBody(user);
            if (body != null) {
                prefixes.add(new IntentPrefixLine(IntentPrefixKind.PROFILE,
                  PROFILE_LABEL + "\n" + body));
            }
        }
        if (user.isMatchIncludePortfolio() && isPresent(user.getPortfolio())) {
            prefixes.add(new IntentPrefixLine(IntentPrefixKind.PORTFOLIO,
              PORTFOLIO_LABEL + "\n" + user.getPortfolio().strip()));
        }
        return prefixes;
    }

    /**
     * 학교·캠퍼스·단과대·전공·학년·관심직무·한 줄 소개. 이름·이메일은 넣지 않는다.
     *
     * @return 채울 칸이 하나도 없으면 null
     */
    static String profileBody(User user) {
        StringBuilder body = new StringBuilder();
        appendLine(body, "학교", user.getSchool());
        appendLine(body, "캠퍼스", user.getCampus());
        appendLine(body, "단과대", user.getCollege());
        appendLine(body, "전공", user.getMajor());
        appendLine(body, "학년", user.getGrade());
        appendLine(body, "관심직무", joinJobs(user));
        appendLine(body, "한 줄 소개", user.getTagline());
        return body.isEmpty() ? null : body.toString();
    }

    private static String joinJobs(User user) {
        StringBuilder jobs = new StringBuilder();
        appendJob(jobs, user.getInterestJobPrimary());
        appendJob(jobs, user.getInterestJobSecondary());
        appendJob(jobs, user.getInterestJobTertiary());
        return jobs.isEmpty() ? null : jobs.toString();
    }

    private static void appendJob(StringBuilder jobs, String value) {
        if (!isPresent(value)) {
            return;
        }
        if (!jobs.isEmpty()) {
            jobs.append(", ");
        }
        jobs.append(value.strip());
    }

    private static void appendLine(StringBuilder body, String label, String value) {
        if (!isPresent(value)) {
            return;
        }
        if (!body.isEmpty()) {
            body.append('\n');
        }
        body.append(label).append(": ").append(value.strip());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

}
