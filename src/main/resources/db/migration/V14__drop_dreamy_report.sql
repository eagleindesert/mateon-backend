-- V14: '드림이 리포트'(OpenAI 커리어 분석) 기능 제거.
-- 마이페이지 조회 때마다 OpenAI Chat Completions 를 직접 호출하던 기능인데, 실제로 쓰이지 않는
-- 데다 API 키가 유효하지 않아 매 요청 401 을 냈다. 게다가 실패 시 폴백 JSON("활동 데이터를
-- 충분히 채워주세요")을 그대로 이 컬럼에 저장해버려서, 대부분의 행에는 분석 결과가 아니라
-- 실패 흔적이 캐싱돼 있다. 보존할 가치가 없으므로 컬럼째 드롭한다.
--
-- 프로젝트의 AI 기능은 별도 AI 서버(ai.base-url)로 일원화되어 있고 이 컬럼과는 무관하다.
ALTER TABLE users DROP COLUMN IF EXISTS dreamy_report;
