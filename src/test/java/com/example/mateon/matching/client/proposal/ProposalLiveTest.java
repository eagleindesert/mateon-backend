package com.example.mateon.matching.client.proposal;

import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 제안 조립 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * {@link ProposalResponse} 에서 스네이크 케이스 매핑이 필요한 유일한 필드가
 * {@code synergy_score} 다. 나머지 둘({@code summary}/{@code message})은 이름이 같아
 * 매핑이 깨져도 멀쩡해 보인다 — 즉 <b>이 클래스에서 실질적으로 검증되는 건 synergy_score
 * 하나</b>이고, 그게 이 파일이 존재하는 이유다.
 *
 * <p>
 * {@code synergy_score} 는 우리가 보낸 값을 그대로 돌려받는 에코라, 매핑이 어긋나면
 * 예외가 아니라 null 이 된다. 그리고 이 값은 화면 어디에도 안 나가고 진단용으로만 쓰여서
 * (DTO 주석 참고) 운영에서 null 이 되어도 알아차릴 계기가 없다.
 */
class ProposalLiveTest {

    private ProposalClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        client = new ProposalClient(AiStubSupport.aiCallTemplate());
    }

    @Test
    @DisplayName("user-to-team 초안의 세 필드가 채워진다 (synergy_score 포함)")
    void userToTeamResponseIsMapped() {
        ProposalResponse response = client.userToTeam(request());

        assertThat(response.getSummary()).isNotBlank();
        assertThat(response.getMessage()).isNotBlank();
        assertThat(response.getSynergyScore())
          .as("synergy_score 가 null 이면 @JsonProperty 매핑이 어긋난 것이다")
          .isNotNull();
    }

    /**
     * 스텁은 받은 점수를 그대로 되돌려 준다. 실서버도 에코라는 게 명세다. 값까지 보는 이유는
     * 매핑이 어긋난 채로 우연히 다른 숫자가 들어오는 경우를 배제하기 위해서다.
     */
    @Test
    @DisplayName("synergy_score 는 우리가 보낸 값 그대로다 (조립 단계에서 재계산하지 않는다)")
    void synergyScoreIsEchoedBack() {
        ProposalResponse response = client.userToTeam(request());

        assertThat(response.getSynergyScore()).isEqualTo(0.87);
    }

    @Test
    @DisplayName("team-to-user 도 같은 스키마로 온다 (엔드포인트만 다르다)")
    void teamToUserResponseIsMapped() {
        ProposalResponse response = client.teamToUser(request());

        assertThat(response.getSummary()).isNotBlank();
        assertThat(response.getMessage()).isNotBlank();
        assertThat(response.getSynergyScore()).isNotNull();
    }

    // --- 픽스처 -------------------------------------------------------------
    // ProposalRequestSerializationTest 와 같은 값이다. 스텁이 sender_id/receiver_id 가
    // 방향에 맞는지 직접 대조하므로(user-to-team 이면 sender=유저) 그 조합을 지킨다.

    private ProposalAssemblyRequest request() {
        return new ProposalAssemblyRequest(7L, 42L, 99L, 7L, 42L, 55L, 0.87,
          "커머스 플랫폼, BE 1명 결핍", "React/TypeScript 경험, 초보자");
    }
}
