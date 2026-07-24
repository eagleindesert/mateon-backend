# 카카오 액세스 토큰 자동 획득 (로컬 테스트 전용)

백엔드만으로 `POST /api/auth/social/kakao` 정상 경로를 테스트하려면 실제 카카오 액세스 토큰이
필요하다. 이 도구는 브라우저 로그인 한 번으로 그 토큰을 획득해 **이 폴더의 `.env`**
(`MATEON_KAKAO_ACCESS_TOKEN`)에 기록한다. **DB·docker 의존이 없다** — 인가코드는 브라우저에서
확인해 셸 프롬프트에 붙여넣는다.

## 구성 요소

| 구성 | 위치 | 역할 |
|------|------|------|
| 디버그 컨트롤러 | `com/example/mateon/debug/oauth/OAuthDebugController` | 카카오 리다이렉트(`/debug/oauth?code=...`)를 받아 **완료 페이지에 인가코드를 노출** |
| 교환 셸 | `get-kakao-token.ps1` | 붙여넣은 인가코드 → **access token 교환** → 이 폴더 `.env` 기록 (완전 자립, 외부 스크립트 의존 없음) |

> 컨트롤러는 `debug.oauth.enabled=true` 일 때만 활성화된다(기본 미등록 → 404). **실배포 무영향.**
> 인가코드는 완료 페이지의 `code:` 값 또는 주소창 `?code=...` 에서 확인한다(DB 조회 불필요).

## 사전 준비

1. **카카오 개발자 콘솔**
   - 앱 REST API 키 확보.
   - Redirect URI 에 `http://localhost:8080/debug/oauth` 등록.
   - (선택) 보안 → client secret 사용 시 그 값도 확보.
2. **루트 `.env`** (백엔드가 읽음, `spring.config.import` 로 로드)
   ```ini
   debug.oauth.enabled=true
   ```
3. **이 폴더의 `.env`** (`get-kakao-token.ps1` 이 읽고, 획득한 토큰도 여기 기록, **가장 우선**)
   `.env.example` 을 복사해 값을 채운다. 카카오 앱 시크릿은 여기 한 곳에만 둔다
   (셸 환경변수보다 이 `.env` 가 이긴다).
   ```ini
   # scripts/test/debug/oauth/.env
   MATEON_KAKAO_REST_API_KEY=<카카오 REST API 키>
   # 선택 (콘솔에서 client secret 사용 시)
   MATEON_KAKAO_CLIENT_SECRET=<client secret>
   # 기본값과 다를 때만
   # MATEON_KAKAO_REDIRECT_URI=http://localhost:8080/debug/oauth
   ```
   모든 `.env` 는 `.gitignore(*.env)` 로 커밋 제외된다(`.env.example` 은 템플릿이라 커밋됨).

## 실행 순서

```powershell
# 0) 백엔드 기동 (debug.oauth.enabled=true 상태로)
./gradlew bootRun

# 1) 교환 셸 실행 → 출력되는 authorize URL 을 브라우저로 열어 카카오 로그인
powershell -ExecutionPolicy Bypass -File .\get-kakao-token.ps1
#    로그인하면 /debug/oauth 완료 페이지가 뜨고, 페이지의 code 값(또는 주소창 ?code=...)이 인가코드.
#    그 인가코드를 같은 셸의 프롬프트에 붙여넣으면 → access token 교환 후 이 폴더 .env 에 기록됨.

# 2) 실제 카카오 로그인 정상 경로 테스트 (토큰은 이 폴더 .env 에만 있으므로 -KakaoAccessToken 으로 전달)
powershell -ExecutionPolicy Bypass -File ..\..\for-api\auth\08_social_kakao.ps1 -KakaoAccessToken <획득한_토큰>
```

> 인가코드는 일회용·단기 만료다. `access token 교환 실패`가 뜨면 authorize 부터 다시 한다.
