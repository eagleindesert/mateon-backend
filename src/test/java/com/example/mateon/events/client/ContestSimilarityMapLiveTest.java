package com.example.mateon.events.client;

import com.example.mateon.events.client.ContestSimilarityMapRequest.ContestItem;
import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유사도 지도 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * 값이 아니라 모양만 단정한다. 스텁 payload 는 언제든 손볼 수 있어서 좌표를 단정하면
 * 스텁을 고칠 때마다 깨진다.
 */
class ContestSimilarityMapLiveTest {

    private ContestSimilarityMapClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        client = new ContestSimilarityMapClient(AiStubSupport.aiCallTemplate());
    }

    @Test
    @DisplayName("빈 후보면 points 와 reference_rings 가 빈 배열이다")
    void emptyCandidatesMapToEmptyPoints() {
        ContestSimilarityMapResponse response = client.map(new ContestSimilarityMapRequest(
          query(), List.of(), 500));

        assertThat(response.getQuery()).isNotNull();
        assertThat(response.getQuery().getId()).isEqualTo("325453");
        assertThat(response.getPoints()).isEmpty();
        assertThat(response.getReferenceRings()).isEmpty();
        assertThat(response.getCandidatePoolTotal()).isZero();
        assertThat(response.getMaxRadius()).isEqualTo(12.0);
        assertThat(response.getMinRadius()).isEqualTo(2.6);
        assertThat(response.getRadialJitter()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("후보 1건이면 points 좌표와 reference_rings 가 채워진다")
    void oneCandidateMapsCoordinates() {
        ContestSimilarityMapResponse response = client.map(new ContestSimilarityMapRequest(
          query(), List.of(candidate()), 500));

        assertThat(response.getPoints()).hasSize(1);
        ContestSimilarityMapResponse.Point point = response.getPoints().get(0);
        assertThat(point.getId()).isEqualTo("328417");
        assertThat(point.getSimilarity()).isNotNull();
        assertThat(point.getRankPercentile()).isNotNull();
        assertThat(point.getRadius()).isNotNull();
        assertThat(point.getX()).isNotNull();
        assertThat(point.getY()).isNotNull();
        assertThat(point.getFieldLabel()).isNotBlank();
        assertThat(response.getReferenceRings()).isNotEmpty();
        assertThat(response.getCandidatePoolTotal()).isEqualTo(1);
    }

    private static ContestItem query() {
        return new ContestItem("325453", new float[]{0.5f, -0.25f},
          "제1회 대학생 국제기구 입찰 경진대회",
          "(사)정부조달수출진흥협회", "CONTEST", "EDUCATION",
          "https://linkareer.com/activity/325453");
    }

    private static ContestItem candidate() {
        return new ContestItem("328417", new float[]{0.25f, 0.75f},
          "한솔그룹 AI 숏폼 공모전", "한솔그룹", "CONTEST", "PLANNING_IDEA",
          "https://linkareer.com/activity/328417");
    }
}
