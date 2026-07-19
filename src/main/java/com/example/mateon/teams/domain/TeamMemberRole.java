package com.example.mateon.teams.domain;

public enum TeamMemberRole {
    LEADER,  // 팀 개설자. teams.leader_user_id 와 이중 기록된다.
    MEMBER   // 지원 후 승인된 팀원
}
