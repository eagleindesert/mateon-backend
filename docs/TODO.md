- maching 기능의 client 디렉토리 안의 파일들을 디렉토리들로 깔끔하게 정리하기

- config를 common 안으로 넣기

- properties를  yml로 변환하기. 더러워짐

- .env안에 production과 debug 구분하기

- 공통 AI 클래스는 common 으로

- UserResponse 에서 from 오버로딩부분 손보기, 및 /api/users/mypage 현재 프론트에서 안쓰므로 deprecated 처리 하기

- boolean 응답 키를 `is` 접두로 통일하기. 지금은 한 응답 안에서 이름 규칙이 섞여 있음
    - 최상위는 접두어가 떨어진 `leader` / `recruiting` (자바 필드가 `is-` 로 시작해 Jackson 이 뗀 것)
    - 반면 `isEnded` 와 `members[].isLeader` 는 `@JsonProperty` 로 `is` 를 붙여 둠
    - 프론트가 현재 `leader` / `recruiting` 을 읽고 있어 서버만 바꾸면 조용히 깨짐 → **프론트와 동시에** 전환해야 함
    - 대상: `TeamDetailResponseDTO.isLeader`, `TeamResponseDTO.isRecruiting` (목록 응답도 같이 바뀜)
    - 바꿀 때: `@JsonProperty` 는 필드가 아니라 **게터**에 달 것 (필드에 달면 두 키가 모두 나감).
      `TeamDetailResponseSerializationTest` 와 `05_team.ps1` 5.2f/5.2g 의 assert 방향도 함께 뒤집기