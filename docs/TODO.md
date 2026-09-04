- [ ] boolean 응답 키를 `is` 접두로 통일하기 — 과도기 상태. 남은 일은 옛 키 제거
  - 지금은 최상위 `leader` / `recruiting` 과 `isLeader` / `isRecruiting` 이 같은 값으로 **함께** 나감
    (`TeamDetailResponseDTO.getIsLeader()`, `TeamResponseDTO.getIsRecruiting()`)
  - 프론트가 `isLeader` / `isRecruiting` 으로 옮긴 것을 확인한 뒤 `leader` / `recruiting` 을 뺀다
  - 뺄 때: 직접 선언한 `isLeader()` / `isRecruiting()` 게터만 지우면 됨. `getIsLeader()` 가 있으면
    Lombok 이 그 필드의 게터를 만들지 않아 옛 키가 되살아나지 않음 (직접 선언한 이유가 그것).
    `TeamDetailResponseSerializationTest`, `TeamControllerTest`, `05_team.ps1` 5.2f/5.2g 의 assert 를
    "옛 키가 없다" 로 뒤집기

- JPA 테이블이 담긴 `domain` 을 `entity`로 바꾸기
  - 애초에, auth, bookmarks, events, mail.. 등이 도메인이다!
  - 이미 도메인이 나뉘었으므로, 각 도메인의 테이블인 `entity` 디렉토리 안에 속한 것이 의미상 적절하다.

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
