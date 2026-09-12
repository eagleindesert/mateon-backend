package com.example.mateon.aichat.domain;

/**
 * 매칭 의도 추출에 실리는 접두 메시지의 출처.
 *
 * <p>
 * {@code NULL} 이 일반 턴이다. 값이 있으면 FastAPI {@code /intents/extract} 배열 앞에 붙는
 * {@code [자기소개서]}/{@code [포트폴리오]} 행이고, 채팅 복원·사이드바에는 내보내지 않는다.
 */
public enum IntentPrefixKind {
    PROFILE,
    PORTFOLIO
}
