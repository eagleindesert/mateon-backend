# .java 코드 포맷

VSCode `Oracle.oracle-java` 포맷터 설정(`indent-shift-width: 4`,
`continuationIndentSize: 2`)에 맞춘다. 저장 시 Format Document 가 도는 프로젝트라,
아래를 지키지 않으면 다음 저장에서 전부 다시 써진다.

## 들여쓰기

- 블록 들여쓰기는 스페이스 4칸. 탭 금지.
- **줄이 넘쳐 이어질 때(continuation)는 문장 시작 줄 기준 2칸만 더 들여쓴다.** 8칸이 아니다.
  - 메서드 체이닝, 대입문 우변 줄바꿈, 인자 목록 줄바꿈 모두 +2칸
  - 이어지는 줄 사이에 낀 `//` 주석도 같은 +2칸에 맞춘다
- 메서드 파라미터가 한 줄에 안 들어가면 파라미터마다 한 줄씩 +2칸,
  닫는 `) {` 는 메서드 선언과 같은 열에 둔다.

## Javadoc

- 한 줄로 끝나는 내용도 `/**`, 본문, `*/` 세 줄로 쓴다. `/** 내용 */` 한 줄 형태 금지.
- `<p>` 는 단독 줄에 두고 본문은 그다음 줄부터 시작한다.
- 본문 텍스트의 줄바꿈 위치는 그대로 유지된다(포맷터가 리플로우하지 않는다).
  한글 주석은 화면 폭을 보고 손으로 끊는다. 열 제한은 강제되지 않는다.
- 텍스트 블록(`"""`) 내부는 건드리지 않는다.

## 기타

- 파일 끝에 개행 하나를 둔다.

## 예시

```java
/**
 * 페이지 파라미터 정규화. 클라이언트가 보낸 page/size 를 그대로 믿지 않고 여기서 자른다.
 *
 * <p>
 * 북마크 목록도 같은 규칙을 써야 해서 EventService 밖으로 꺼냈다.
 */
public ResponseEntity<BaseResponse<BookmarkToggleResponseDTO>> addBookmark(
  Authentication authentication,
  @PathVariable Long eventId
) {
    Long userId = Long.valueOf(authentication.getName());
    return ResponseEntity.status(status)
      .body(BaseResponse.success(message, new BookmarkToggleResponseDTO(eventId, true)));
}
```
