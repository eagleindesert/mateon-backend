- `[ ]` `채팅 기능` 추가

- `[ ]` 소셜 로그인 기능 추가

- `[ ]` .env 리팩토링.
    - 모든 환경변수 파일 이름을 .env로
    - docker compose의 환경변수는 모두 제거하고, .env로 통일
    - application.properties 도 깔끔하게 정리. 
        - spring.config.import=optional:file:.env[.properties] 이거 못없애나? 
