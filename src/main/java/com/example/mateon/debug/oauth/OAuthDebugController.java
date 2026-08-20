package com.example.mateon.debug.oauth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [로컬 테스트 전용] 카카오 인가코드 수신용 디버그 컨트롤러.
 *
 * 카카오 authorize 후 브라우저가 redirect_uri(=/debug/oauth?code=...)로 이동하면,
 * 그 인가코드를 DB(oauth_debug_codes)에 저장한다. 이후 get-kakao-token.ps1 이 이 코드를
 * 읽어 access token 으로 교환한다.
 *
 * 실배포 노출 방지: debug.oauth.enabled=true 일 때만 빈으로 등록된다(기본 미등록 → 404).
 * 로컬에서는 루트 .env 에 debug.oauth.enabled=true 를 넣어 활성화한다.
 */
@Tag(name = "[디버그] OAuth", description = "로컬 전용. debug.oauth.enabled=true 일 때만 등록된다")
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "debug.oauth.enabled", havingValue = "true")
public class OAuthDebugController {

    private final OAuthDebugCodeRepository repository;

    @Operation(summary = "[디버그] 카카오 인가코드 수신",
      description = """
                          **로컬 테스트 전용이며 프론트가 부를 일이 없다.**
                          `debug.oauth.enabled=true` 일 때만 빈으로 등록되므로, 그 밖에서는 이
                          경로 자체가 404 다.

                          카카오 authorize 후 브라우저가 redirect_uri 로 이동하면 그 인가코드를
                          DB 에 저장하고, `get-kakao-token.ps1` 이 그걸 읽어 access token 으로
                          교환한다. 응답은 JSON 이 아니라 안내용 HTML 이다.""")
    @Parameter(name = "code", description = "카카오가 redirect_uri 에 붙여 주는 인가코드.")
    @SecurityRequirement(name = "")  // 비로그인 허용 (/debug/** permitAll)
    @GetMapping("/debug/oauth")
    public ResponseEntity<String> receiveCode(@RequestParam("code") String code) {
        // 항상 최신 코드 하나만 남긴다(셸이 LIMIT 1 로 읽지만, 누적 방지).
        repository.deleteAllInBatch();
        repository.save(OAuthDebugCode.builder().code(code).build());

        String html = "<html><head><meta charset=\"utf-8\"></head><body style=\"font-family:sans-serif\">"
          + "<h2>✅ 인가코드 저장 완료</h2>"
          + "<p>이제 터미널에서 <code>get-kakao-token.ps1</code> 을 실행하세요.</p>"
          + "<p style=\"color:#888\">code: " + code + "</p>"
          + "</body></html>";
        return ResponseEntity.ok()
          .contentType(MediaType.TEXT_HTML)
          .body(html);
    }
}
