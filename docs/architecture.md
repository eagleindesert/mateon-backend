# Mateon Backend — 아키텍처

**Mateon** 은 대학생 대상 팀 매칭 / 활동 모집 서비스의 백엔드입니다.
사용자 인증(로컬 + 소셜 대비 + 학교 재학생 인증), 활동(이벤트) 추천, 팀 모집·지원, 실시간 알림(SSE), AI 커리어 분석을 REST API 로 제공합니다.

- **런타임**: Java 21 / Spring Boot 4.0
- **핵심 스택**: Spring Web MVC · Spring Security(JWT, Stateless) · Spring Data JPA · PostgreSQL 16
- **외부 연동**: Gmail SMTP(이메일 인증) · OpenAI Chat Completions(커리어 분석) · SSE(실시간 알림)
- **문서**: springdoc OpenAPI(Swagger UI) / API 명세는 [api-spec.md](api-spec.md), DB 스키마는 [schema-db.md](schema-db.md)

---

## 1. 시스템 컨텍스트

```mermaid
graph TB
    subgraph Clients["클라이언트"]
        WEB["웹 프론트엔드<br/>(localhost:3000 / 5173)"]
    end

    subgraph Backend["Mateon Backend (Spring Boot :8080)"]
        API["REST API + SSE"]
    end

    subgraph Infra["인프라"]
        DB[("PostgreSQL 16<br/>mateon_db")]
    end

    subgraph External["외부 서비스"]
        SMTP["Gmail SMTP<br/>이메일 인증코드 발송"]
        OPENAI["OpenAI API<br/>드림이 커리어 분석"]
    end

    WEB -->|"HTTPS / JSON<br/>Bearer JWT"| API
    API -.->|"SSE (text/event-stream)<br/>실시간 알림 push"| WEB
    API -->|JPA / JDBC| DB
    API -->|SMTP| SMTP
    API -->|"HTTPS REST"| OPENAI
```

---

## 2. 레이어드 아키텍처

전형적인 Controller → Service → Repository → Entity 3계층 구조이며, 도메인별 패키지로 수직 분할되어 있습니다.

```mermaid
graph TB
    subgraph Security["보안 필터 체인 (Stateless)"]
        JWTF["JwtAuthenticationFilter<br/>Bearer 토큰 → userId 인증"]
        CORS["CORS / CSRF disable"]
    end

    subgraph Controller["Controller 계층 (@RestController)"]
        AC["AuthController<br/>/api/auth"]
        UC["UserController<br/>/api/users"]
        EC["EventController<br/>/api/events"]
        TC["TeamController<br/>/api/teams"]
        NC["NotificationController<br/>/api/notifications"]
        HC["HealthController<br/>/health"]
    end

    subgraph Service["Service 계층 (@Service, @Transactional)"]
        AS["AuthService"]
        US["UserService"]
        EMS["EventMatchingService"]
        TS["TeamService"]
        NS["NotificationService"]
        MS["MailService"]
        OAS["OpenAiService"]
    end

    subgraph Repository["Repository 계층 (Spring Data JPA)"]
        UR["UserRepository"]
        EVR["EmailVerificationRepository"]
        RTR["RefreshTokenRepository"]
        ER["EventRepository"]
        TR["TeamRepository"]
        TAR["TeamApplicationRepository"]
        NR["NotificationRepository"]
        EMR["EmitterRepository<br/>(인메모리 SSE)"]
    end

    DB[("PostgreSQL")]

    Controller --> Security
    AC --> AS
    UC --> US
    EC --> EMS
    TC --> TS
    NC --> NS

    AS --> UR & EVR & RTR & MS
    US --> UR & TAR & ER & OAS
    TS --> TR & TAR & ER & UR & NS
    NS --> NR & UR & EMR
    EMS --> ER

    UR & EVR & RTR & ER & TR & TAR & NR --> DB
```

### 패키지 구조 (도메인 수직 분할)

```
com.example.mateon
├── auth          인증: 로그인/회원가입/JWT/이메일·학교 인증
│   ├── controller · service · jwt(JwtTokenProvider, JwtAuthenticationFilter)
│   ├── domain(EmailVerification, RefreshToken) · dto · repository
├── user          사용자 프로필/마이페이지/AI 분석
│   ├── controller · service(UserService, OpenAiService)
│   ├── domain(User, AuthProvider) · dto · repository
├── events        활동(이벤트) 조회·추천 매칭
│   ├── controller · service(EventMatchingService)
│   ├── models(Event) · dto · repository
├── teams         팀 모집·지원 관리
│   ├── controller · service · converter(RoleListConverter)
│   ├── domain(Team, TeamApplication, ApplicationStatus) · dto · repository
├── notification  SSE 실시간 알림
│   ├── controller · service · domain(Notification)
│   ├── dto · repository(NotificationRepository, EmitterRepository)
├── mail          Gmail SMTP 발송 (MailService)
├── common        공통 응답(ApiResponse)·예외(MateonException, ErrorCode)·헬스체크
└── config        SecurityConfig · JwtProperties · RestTemplateConfig
```

---

## 3. 인증 · 인가 흐름

Stateless JWT 기반. 모든 요청은 `JwtAuthenticationFilter` 를 거치며, 유효한 Bearer 토큰이 있으면
`Authentication.getName()` 에 **userId** 가 담깁니다. (이메일이 아닌 userId 기준으로 인증 — 소셜 로그인 대비)

```mermaid
sequenceDiagram
    participant C as Client
    participant F as JwtAuthenticationFilter
    participant SC as SecurityFilterChain
    participant Ctrl as Controller
    participant Svc as Service

    C->>F: 요청 + Authorization: Bearer <accessToken>
    F->>F: 토큰 추출·검증(JwtTokenProvider)
    alt 유효한 토큰
        F->>F: userId 추출 → SecurityContext 에 인증 저장 (ROLE_USER)
    else 토큰 없음/무효
        F->>F: 인증 미설정 (익명)
    end
    F->>SC: 필터 체인 진행
    SC->>SC: 경로별 인가 판정
    Note over SC: permitAll: /health, /api/auth/**(school 제외), /api/events/**, swagger<br/>authenticated: /api/auth/school/**, /api/users/**, /api/events/recommended, 그 외
    alt 인가 통과
        SC->>Ctrl: 요청 전달
        Ctrl->>Svc: userId = Long.valueOf(auth.getName())
        Svc-->>C: ApiResponse<T>
    else 인가 실패
        SC-->>C: 401 / 403
    end
```

### 회원가입 흐름 (소셜 우선 · 학교 인증 후행 재설계)

로컬 가입은 **이메일 인증 → 회원가입** 순서로 진행되며, 로컬 가입 유저는 가입 시점에 학교(재학생) 인증까지 확정됩니다.
소셜 유저는 로그인 후 별도로 `/api/auth/school/**` 에서 학교 이메일 인증을 거쳐 재학생(`school_verified=true`)이 됩니다.

```mermaid
sequenceDiagram
    participant C as Client
    participant AC as AuthController
    participant AS as AuthService
    participant M as MailService
    participant DB as PostgreSQL

    C->>AC: POST /api/auth/email/request (email)
    AC->>AS: 도메인(@dankook.ac.kr) 검증
    AS->>DB: email_verifications upsert (code, +5분)
    AS->>M: 인증코드 메일 발송
    M-->>C: 📧 6자리 코드

    C->>AC: POST /api/auth/email/verify (email, code)
    AS->>DB: 코드 대조 → verified=true

    C->>AC: POST /api/auth/signup (프로필 + 비밀번호)
    AS->>DB: verified 확인 → users insert<br/>(school_verified=true, provider=LOCAL)
    AS->>DB: refresh_tokens insert
    AS-->>C: TokenResponse (access + refresh)
```

---

## 4. 핵심 도메인 흐름

### 활동(이벤트) 추천 매칭

`EventMatchingService` 가 사용자 프로필과 이벤트를 가중치 기반으로 스코어링해 정렬/추천합니다. (DB 조회 후 인메모리 스코어링)

```mermaid
graph LR
    U["User 프로필<br/>희망직무·전공·단과대·캠퍼스"] --> S
    E["Event<br/>제목·설명·대상·campusScope"] --> S
    S["calculateRelevanceScore()"] --> R["관련도 점수"]
    R --> SORT["점수 내림차순 → 동점 시 최신순"]

    subgraph Weights["가중치"]
        W1["희망직무 1/2/3순위: 30 / 20 / 10"]
        W2["전공 매칭: 15"]
        W3["단과대 매칭: 10"]
        W4["캠퍼스 매칭: 5"]
    end
```

- `GET /api/events/search` — 필터(단과대/카테고리) 적용 후, 로그인 상태면 관련도순 정렬.
- `GET /api/events/recommended` — 카테고리별 최고 점수 활동 1개씩 추천(**인증 필요**).

### 팀 지원 → 승인/거절 → 실시간 알림

팀 모집·지원 액션은 **학교 인증(재학생)** 이 완료된 유저만 가능(`requireSchoolVerified`).
팀장이 지원서를 처리하면 `NotificationService` 가 DB 영속화와 SSE 실시간 push 를 동시에 수행합니다.

```mermaid
sequenceDiagram
    participant AP as 지원자
    participant LD as 팀장
    participant TC as TeamController
    participant TS as TeamService
    participant NS as NotificationService
    participant EM as EmitterRepository (인메모리)
    participant DB as PostgreSQL

    Note over AP: (사전) GET /api/notifications/subscribe → SSE 연결 유지
    AP->>EM: SSE Emitter 등록

    AP->>TC: POST /api/teams/{id}/apply
    TC->>TS: applyToTeam (재학생 검증·중복 검증)
    TS->>DB: team_applications insert (PENDING)

    LD->>TC: PATCH /api/teams/applications/{id}?isApproved=true
    TC->>TS: processApplication
    TS->>DB: status = APPROVED/REJECTED
    alt 승인
        TS->>DB: 지원자 dreamy_report=null (재분석 유도)
        TS->>TS: 승인 인원 ≥ 정원 시 is_recruiting=false
    end
    TS->>NS: send(지원자, 제목, 내용, type)
    NS->>DB: notification insert
    NS->>EM: 접속 중이면 SSE push
    EM-->>AP: 📨 실시간 알림
```

### 마이페이지 & AI 커리어 분석 ('드림이')

```mermaid
graph TB
    REQ["GET /api/users/mypage"] --> US["UserService.getMyPage"]
    US --> ACT["승인된 지원서 → 참여 활동 집계<br/>(team_applications + teams + events)"]
    US --> CACHE{"user.dreamy_report<br/>존재?"}
    CACHE -->|있음| PARSE["저장된 JSON 파싱"]
    CACHE -->|없음| AI["OpenAiService.generateDreamyAnalysis<br/>→ OpenAI Chat Completions"]
    AI --> SAVE["결과 JSON 을 users.dreamy_report 에 캐싱"]
    PARSE --> RESP["MyPageResponseDTO<br/>(프로필 + 활동 + AI 분석)"]
    SAVE --> RESP
```

> 프로필 수정 또는 새 활동 승인 시 `dreamy_report` 를 `null` 로 비워 다음 조회 때 재분석되도록 유도합니다.

---

## 5. 공통 규약

- **응답 포맷**: 모든 API 는 `ApiResponse<T>` 로 감싸 반환 (`success`, `message`, `data`).
- **예외 처리**: `MateonException` + `ErrorCode` 를 `GlobalExceptionHandler` 에서 일괄 변환.
- **감사 필드**: `@CreatedDate` / `@LastModifiedDate` (JPA Auditing) 또는 `@PrePersist`/`@PreUpdate` 로 타임스탬프 관리.
- **스키마 관리**: `ddl-auto=update` — 엔티티 변경이 곧 스키마 변경. 상세는 [schema-db.md](schema-db.md).

---

## 6. 배포 토폴로지

로컬 개발은 `bootRun` 이 `compose-dev.yml`(PostgreSQL + pgAdmin)을 자동 기동합니다.
배포는 멀티스테이지 Docker 이미지(빌드 amd64 네이티브 → 실행 arm64)를 DockerHub 로 push 후, ARM 클라우드에서 compose 로 실행합니다.

```mermaid
graph TB
    subgraph Dev["로컬 개발 (compose-dev.yml)"]
        BR["gradlew bootRun<br/>Spring Boot :8080"]
        PGD[("postgres:16<br/>:5432")]
        PGA["pgAdmin :5050"]
        BR -->|자동 기동| PGD
        PGA --> PGD
    end

    subgraph Build["빌드/배포 파이프라인"]
        DF["Dockerfile (멀티스테이지)<br/>BUILDPLATFORM 빌드 → JRE 21 런타임"]
        HUB["DockerHub<br/>eagleindesert/mateon-backend:tag (arm64)"]
        DF --> HUB
    end

    subgraph Prod["ARM 클라우드 (docker-compose-deployment.yml)"]
        APP["mateon-app<br/>:8081→8080"]
        PGP[("mateon-postgres<br/>:5433→5432<br/>volume 영속화")]
        APP -->|"postgres:5432"| PGP
    end

    HUB -->|pull| APP
```

| 환경 | Compose 파일 | 앱 포트 | DB 포트 | 비고 |
| --- | --- | --- | --- | --- |
| 로컬 개발 | `compose-dev.yml` | 8080 (bootRun) | 5432 | pgAdmin 5050 포함 |
| 배포(ARM) | `docker-compose-deployment.yml` | 8081→8080 | 5433→5432 | DockerHub 이미지 pull, 시크릿은 `.env` 주입 |

- **시크릿 주입**: `MAIL_*`, `JWT_*`, `OPENAI_*` 는 `.env`(로컬) 또는 compose `env_file`/`environment`(배포)로 주입.
- **DataSource**: 배포 시 `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/mateon_db` 로 compose 네트워크 내 서비스명 접속.
