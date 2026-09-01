- [ ] .env안에 production과 debug 구분하기
  - [x] 관련해서 다뤄야할 설정 먼저 살피기

- [ ] boolean 응답 키를 `is` 접두로 통일하기. 지금은 한 응답 안에서 이름 규칙이 섞여 있음
  - 최상위는 접두어가 떨어진 `leader` / `recruiting` (자바 필드가 `is-` 로 시작해 Jackson 이 뗀 것)
  - 반면 `isEnded` 와 `members[].isLeader` 는 `@JsonProperty` 로 `is` 를 붙여 둠
  - 프론트가 현재 `leader` / `recruiting` 을 읽고 있어 서버만 바꾸면 조용히 깨짐 → **프론트와 동시에** 전환해야 함
  - 대상: `TeamDetailResponseDTO.isLeader`, `TeamResponseDTO.isRecruiting` (목록 응답도 같이 바뀜)
  - 바꿀 때: `@JsonProperty` 는 필드가 아니라 **게터**에 달 것 (필드에 달면 두 키가 모두 나감).
    `TeamDetailResponseSerializationTest` 와 `05_team.ps1` 5.2f/5.2g 의 assert 방향도 함께 뒤집기

- JPA 테이블이 담긴 `domain` 을 `entity`로 바꾸기
  - 애초에, auth, bookmarks, events, mail.. 등이 도메인이다!
  - 이미 도메인이 나뉘었으므로, 각 도메인의 테이블인 `entity` 디렉토리 안에 속한 것이 의미상 적절하다.

- 필수 쿼리 파라미터를 빠뜨리면 400 이 아니라 **500** 이 나감
  - `GlobalExceptionHandler` 에 `MissingServletRequestParameterException` 핸들러가 없어 catch-all
    `Exception` (500) 으로 떨어짐. 같은 성격인 타입 불일치(`?teamId=abc`)는 전용 핸들러가 있어 400 임
  - 재현: `GET /api/matching/recommendations/team-to-user` 를 `teamId` 없이 호출
  - 영향: 클라이언트 실수가 5xx 알람을 울리고, 프론트에는 "잠시 후 다시 시도" 안내가 뜸(고쳐도 영원히 안 됨)
  - 고칠 때: `handleTypeMismatch` 와 같은 봉투(`message:"입력값 검증에 실패했습니다.", data:{파라미터명:사유}`)로
    맞출 것. `RecommendationControllerTest.missingTeamIdFallsToCatchAll` 이 지금 동작을 고정하고 있으므로
    함께 뒤집기

- `BaseResponse.success(...)` 오버로드 때문에 응답 봉투가 두 모양으로 갈려 있음
  - 데이터가 없는 엔드포인트가 `BaseResponse.success("삭제되었습니다.")` 를 호출하는데, 인자가 하나라
    `success(T data)` 에 묶여 **사람이 읽을 문구가 `data` 로 가고 `message` 는 `"성공"`** 이 됨
  - 대상: `TeamController` 의 `DELETE /api/teams/{id}`, `/apply`, `/applications/{id}` (PATCH/PUT/DELETE),
    `TeamOfferController` 의 `DELETE /api/teams/offers/{id}`,
    `AuthController` 의 `/email/request`, `/school/email/request`, `/school/email/verify`,
    `/password/change`, `/logout`
  - 반면 `TeamReviewController` 는 `success(null)` 을 써서 `message:"성공"` + `data:null` 로 정상임
  - 프론트가 현재 `data` 에서 문구를 읽고 있을 수 있어 **프론트와 동시에** 전환해야 함
  - 고칠 때: `success(String message, T data)` 로 바꾸고 `TeamControllerTest`,
    `TeamOfferControllerTest`, `AuthControllerTest` 의 `$.data` / `$.message` assert 를 함께 뒤집기
    (`AuthControllerTest.messagePlacementDiffersBetweenEndpoints` 가 두 모양을 나란히 보여 줌)
  - 11번의 `UserResponse.from` 오버로딩 정리와 성격이 같음

- `createTeam` 의 활동 존재 확인이 저장보다 **뒤**에 있음
  - 순서: `teamRepository.save()` → LEADER 멤버 행 save → 임베딩 이벤트 발행 → 그제야 `eventId` 조회
  - 지금은 같은 트랜잭션이라 롤백돼 피해가 없지만, `@Transactional` 이 떨어지거나 메서드를 쪼개면
    고아 팀이 남음. 임베딩 재계산 이벤트도 존재하지 않는 팀에 대해 발행됨
  - 고칠 때: `requireSchoolVerified` 직후로 `eventRepository.findById` 를 올리기.
    `TeamServiceCrudTest.unknownEventFailsAfterSave` 가 지금 순서를 고정하고 있으므로 함께 뒤집기

- [x] !!중요!! 공모전 임베딩 백필 스케줄러 종료 조건 추가
  - 벡터가 있는 활동은 후보가 아니다 (성공이든, 성공 후 실패한 낡은 값이든). 재임베딩이 아님
  - 행이 없거나 embedding 이 NULL 인 것만 집어 채운다
  - 연속 실패가 `event-embedding-backfill-max-failures`(기본 8) 이상이면 그 행은 끝낸다
  - `last_attempted_at` 이 `event-embedding-backfill-retry-cooldown`(기본 10m) 안이면 이번 틱 스킵.
    앞 id 가 고여 뒤가 LIMIT 밖으로 밀리는 것을 막는다
  - 빈 틱에서 스케줄을 끄지 않는다. 재시작 후 @Async 큐 유실을 회수해야 한다
