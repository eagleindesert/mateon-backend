package com.example.mateon.teams.dto.response;

import com.example.mateon.MateonBackendApplication;
import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.user.domain.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팀 상세 응답의 JSON 키를 고정한다.
 *
 * <p>필드가 있느냐가 아니라 <b>어떤 이름으로 나가느냐</b>를 확인하는 테스트다. 자바 필드명이
 * {@code is-} 로 시작하면 Lombok 게터({@code isEnded()})에서 Jackson 이 접두어를 떼어
 * {@code ended} 로 내보낼 수 있고, 이 프로젝트는 Jackson 2 와 3 이 함께 클래스패스에 있어
 * (Boot 4 는 3, jjwt-jackson 이 2 를 끌고 옴) 어느 쪽이 잡히느냐에 따라 결과가 달라진다.
 * UserProfileResponse.isMe 가 {@code @JsonProperty} 를 달아야 했던 사정이 그것이다.
 *
 * <p>앱과 같은 ObjectMapper 설정을 쓴다 — 별도로 만든 매퍼로 검증하면 정작 실제 응답과 다른
 * 걸 확인하게 된다.
 */
class TeamDetailResponseSerializationTest {

    private final ObjectMapper objectMapper = new MateonBackendApplication().objectMapper();

    private JsonNode serialize() throws Exception {
        Team team = new Team();
        team.setId(1L);
        team.setTitle("테스트 팀");
        team.setCapacity(4);
        team.setIsRecruiting(true);
        team.setLeaderUserId(10L);

        User leader = User.builder().id(10L).name("김팀장").major("컴퓨터공학").build();
        User member = User.builder().id(20L).name("이팀원").major("통계학").build();

        List<TeamMember> members = List.of(
                TeamMember.of(team, leader, TeamMemberRole.LEADER),
                TeamMember.of(team, member, TeamMemberRole.MEMBER));

        TeamDetailResponseDTO dto = new TeamDetailResponseDTO(
                team, null, members, true, ApplicationStatus.PENDING, leader, new BigDecimal("36.5"));

        return objectMapper.readTree(objectMapper.writeValueAsString(dto));
    }

    @Test
    @DisplayName("문서가 약속한 필드가 약속한 이름으로 나간다")
    void exposesDocumentedFields() throws Exception {
        JsonNode json = serialize();

        assertThat(json.has("members")).isTrue();
        assertThat(json.has("myApplicationStatus")).isTrue();
        // is- 접두어가 떨어지면 "ended" 가 되어 프론트가 못 읽는다.
        assertThat(json.has("isEnded")).isTrue();
        assertThat(json.get("myApplicationStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("isEnded 가 ended 와 이중으로 나가지 않는다")
    void doesNotEmitDuplicateEndedKey() throws Exception {
        // @JsonProperty 를 게터가 아닌 필드에 달면 Lombok 게터가 만든 "ended" 와 병합되지 않아
        // 두 키가 모두 나간다. 같은 값을 이름만 바꿔 두 번 보내는 응답은 어느 쪽이 계약인지 흐린다.
        assertThat(serialize().has("ended")).isFalse();
    }

    @Test
    @DisplayName("최상위 boolean 은 접두어가 떨어진 이름이 정본이다")
    void topLevelBooleansKeepStrippedNames() throws Exception {
        // isEnded 처럼 @JsonProperty 로 "고쳐 주고" 싶어지는 자리다. 고치면 안 된다 —
        // 프론트가 이미 leader / recruiting 을 읽고 있어서, 이름을 바꾸는 순간 조용히 깨진다.
        // 실제로 한 번 isLeader/isRecruiting 으로 바꿨다가 되돌렸다. 이 테스트가 그 재발을 막는다.
        //
        // TODO: 나중에 is 접두로 통일할 예정이다 (docs/TODO.md 참고). 그때 이 테스트를 지우지 말고
        // 방향만 뒤집어라 — 통일 후에는 leader/recruiting 이 다시 나타나지 않는 것을 지켜야 한다.
        // 전환은 프론트와 동시에 해야 하고, 이 테스트가 실패하는 것이 곧 "프론트도 바꿔야 한다"는 신호다.
        JsonNode json = serialize();

        assertThat(json.has("leader")).isTrue();
        assertThat(json.get("leader").asBoolean()).isTrue();
        assertThat(json.has("isLeader")).isFalse();

        // 부모 DTO(TeamResponseDTO)의 필드라 목록 응답도 같은 키를 쓴다.
        assertThat(json.has("recruiting")).isTrue();
        assertThat(json.get("recruiting").asBoolean()).isTrue();
        assertThat(json.has("isRecruiting")).isFalse();
    }

    @Test
    @DisplayName("명단 수가 곧 currentMemberCount 다")
    void memberCountMatchesRoster() throws Exception {
        JsonNode json = serialize();

        assertThat(json.get("members")).hasSize(2);
        assertThat(json.get("currentMemberCount").asInt()).isEqualTo(2);
    }

    /**
     * 명단 쪽은 최상위와 반대로 {@code isLeader} 가 정본이다. record 컴포넌트에
     * {@code @JsonProperty} 가 붙어 있어 처음 나갈 때부터 이 이름이었다.
     * 한 응답 안에 leader(최상위)와 isLeader(명단)가 공존하는 게 의도된 상태다.
     */
    @Test
    @DisplayName("명단은 팀장을 isLeader 로 구분한다 (최상위 leader 와 이름이 다르다)")
    void marksLeaderInRoster() throws Exception {
        JsonNode members = serialize().get("members");

        JsonNode first = members.get(0);
        assertThat(first.has("isLeader")).isTrue();
        assertThat(first.get("isLeader").asBoolean()).isTrue();
        assertThat(first.get("userId").asLong()).isEqualTo(10L);
        assertThat(first.get("name").asText()).isEqualTo("김팀장");

        assertThat(members.get(1).get("isLeader").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("hasApplied 는 myApplicationStatus 에서 파생된다")
    void derivesHasAppliedFromStatus() throws Exception {
        assertThat(serialize().get("hasApplied").asBoolean()).isTrue();

        Team team = new Team();
        team.setId(1L);
        team.setIsRecruiting(true);
        team.setLeaderUserId(10L);
        User leader = User.builder().id(10L).name("김팀장").build();

        TeamDetailResponseDTO notApplied = new TeamDetailResponseDTO(
                team, null, List.of(TeamMember.of(team, leader, TeamMemberRole.LEADER)),
                false, null, leader, null);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(notApplied));
        assertThat(json.get("hasApplied").asBoolean()).isFalse();
        assertThat(json.get("myApplicationStatus").isNull()).isTrue();
    }
}
