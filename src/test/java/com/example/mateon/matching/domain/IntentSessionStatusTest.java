package com.example.mateon.matching.domain;

import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.domain.TaskCloseReason;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 의도 추출 세션 상태의 파생 규칙을 고정한다.
 *
 * <p>
 * V31 에서 이 값은 컬럼에서 사라졌고 {@code (status, closedReason)} 두 컬럼에서 복원한다.
 * 프론트 JSON 이 한 글자도 바뀌지 않으려면 네 값이 빠짐없이 나와야 한다.
 */
class IntentSessionStatusTest {

    @Test
    @DisplayName("살아 있는 작업은 IN_PROGRESS 다 — 닫힘 사유는 보지 않는다")
    void activeIsInProgress() {
        assertThat(IntentSessionStatus.of(task())).isEqualTo(IntentSessionStatus.IN_PROGRESS);
    }

    @ParameterizedTest
    @CsvSource({
      "COMPLETED, COMPLETED",
      "ABANDONED, ABANDONED",
      "EXPIRED, EXPIRED"
    })
    @DisplayName("닫힌 작업은 종료 사유와 1:1 이다")
    void closedMapsFromReason(TaskCloseReason reason, IntentSessionStatus expected) {
        AiDomainTask task = task();
        task.close(reason);

        assertThat(IntentSessionStatus.of(task)).isEqualTo(expected);
    }

    private static AiDomainTask task() {
        User user = User.builder().id(1L).name("김학생").build();
        return new AiDomainTask(new AiChatSession(user), user, RoutableDomain.MATCHING_INTENT);
    }
}
