package com.example.mateon.portfolios.domain;

import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 PDF 를 다시 요약했을 때 행을 갈아끼우지 않고 본문만 바꾸는 자리를 고정한다.
 *
 * <p>
 * 지금 흐름은 캐시 히트면 AI 를 안 부르므로 호출되지 않는다. 프롬프트가 바뀌어 강제
 * 재요약을 넣을 때를 위해 열어 둔 메서드라, 그때 행을 새로 만들면
 * {@code uq_user_portfolios_pair} 가 터진다.
 */
class UserPortfolioTest {

    @Test
    @DisplayName("updateSummary 는 본문만 바꾸고 pdfId 는 그대로다")
    void updateSummaryReplacesTextOnly() {
        User user = User.builder().name("김루미").build();
        UserPortfolio portfolio = new UserPortfolio(user, "abc", "옛 요약", "a.pdf");

        portfolio.updateSummary("새 요약");

        assertThat(portfolio.getSummary()).isEqualTo("새 요약");
        assertThat(portfolio.getPdfId()).isEqualTo("abc");
        assertThat(portfolio.getFilename()).isEqualTo("a.pdf");
        assertThat(portfolio.getUser()).isSameAs(user);
    }
}
