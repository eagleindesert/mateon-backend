---
name: sync-api-spec
description: >
  최신 API 변경 명세서 Last Updated 이후 커밋을 조사한 뒤, 컨트롤러 Swagger
  (title/description)를 먼저 채우고, 그다음 변경 요약 문서와 Swagger 홈화면
  version·description 을 갱신한다.
  Use when the user runs /sync-api-spec, or asks to bump/write an API changelog,
  sync Swagger with api-spec, 변경 명세서, API 문서 새 버전, swagger 동기화.
---

# sync-api-spec

커밋된 변경이 대상이다. 순서는 **커밋 조사 → 컨트롤러 Swagger → 변경 요약 문서**. API 문서는 변경 사항 요약일 뿐이라 정본이 아니고, 스키마·title/description 의 정본은 Swagger 다.

헬퍼 스크립트 없이 `git log` 와 기존 파일을 읽는다.

## 1. 최신 명세

`docs/api-spec/` 의 `v{N}.md` / `v{N}-{M}.md` 만 본다 (`v2-chat.md` 제외). 비교는 `(major, minor)` 이고 minor 가 없으면 0 — `v9` < `v9-2` < `v10`.

최신 파일에서 `Last Updated: YYYY-MM-DD` 를 읽는다. 이 날짜와 최신 버전을 사용자에게 보여 준 뒤, 새 버전 문자열을 받는다. 자동 증가 금지.

- 형식 `^v\d+(-\d+)?$`
- 최신보다 작으면 중단
- 최신과 같으면 그 명세 파일을 나중에 갱신한다 (기존 섹션은 유지하고, 이후 커밋에서 나온 변경만 추가·수정, `Last Updated` 는 오늘)

## 2. 커밋 범위

해당 일자 00:00부터 현재까지 **커밋된 모든 파일**:

```
git log --since="{Last Updated}" --name-status --pretty=format:"=== %h %ad %s ===" --date=short
```

FE 계약 변화가 없으면 여기서 멈추고 그렇게 보고한다.

## 3. 컨트롤러 Swagger

2에서 추가·수정된 `*Controller.java` 와 그 요청/응답 DTO 를 **코드의 실제 계약**으로 채운다. 변경 요약 문서를 기다리지 않는다.

- `@Operation(summary, description)` — summary 가 title
- 그 메서드에 에러 `@ApiResponse` 가 있으면 성공 2xx 도 선언한다. 없으면 springdoc 이 2xx 를 생략한다
- 새로 노출하는 요청/응답 DTO 필드에 `@Schema(description)`
- 이미 충분한 어노테이션은 계약이 바뀐 경우에만 고친다
- Java 포맷은 `Claude.md` (들여쓰기 4, continuation +2, Javadoc 세 줄)

기존 컨트롤러의 `@Operation` / `@ApiResponse` / `@Schema` 말투를 따른다.

## 4. 명세 파일

Swagger 와 코드를 본 뒤에 `docs/api-spec/{version}.md` 를 쓴다. 새 버전이면 생성, 같으면 그 파일을 갱신. 다른 버전 파일은 건드리지 않는다. 템플릿은 최신 명세(현재 `v9-2.md`)를 따른다.

- 제목 `Mateon Backend API 변경 명세서 ({version})`
- Base URL `/`, JWT Bearer, `Last Updated` = 오늘
- 스키마·파라미터·상태 코드는 Swagger 로 이관했다는 안내. 본문은 **직전 버전 대비 FE 기능/정책/동작 요약**만
- 엔드포인트 Request/Response 표는 쓰지 않는다
- 조사한 파일 전부가 재료다. 테스트·스케줄러·내부 클라이언트·테이블/FastAPI 구현 세부는 넣지 않는다. FE 가 알아야 하는 재시도 규칙·에러 코드·응답 계약 변화만 남긴다
- 신규 ErrorCode 가 있으면 표로 정리

## 5. Swagger 홈화면

`OpenApiConfig.mateonOpenAPI()` 의 `Info` 만 바꾼다. 에러 커스터마이저·보안 스키마는 그대로 둔다. 명세를 파일에서 읽어 오게 리팩터하지 않는다.

- `version` = 사용자가 넣은 문자열
- `description` 병기. **직전** = 사용자 버전보다 작은 명세 파일 중 가장 큰 것
  - **윗 단계(메이저) 상승** (`v8→v9`, `v9-2→v10`): 새 버전만
  - **아래 버전 상승** (`v9→v9-2`, `v9-2→v9-3`) 또는 같은 버전 갱신: **최신 + 직전** 둘 다. v9-3 이면 v9-3 뒤에 v9-2 를 붙이고 v9 는 뺀다
- 새 구간: 명세 파일과 같은 전체 헤더 (Base URL, 인증, Last Updated, 안내)
- 직전 구간: `# 제목` + `> Last Updated` + `## 📋` 이후. Base URL / 🔑 / 📌 는 반복하지 않는다
