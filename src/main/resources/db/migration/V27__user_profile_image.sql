-- V27: 사용자 프로필 이미지.
--
-- 객체 저장소의 공개 URL 만 저장하고 객체 키는 따로 두지 않는다. 삭제할 때 필요한 키는 URL 의
-- /o/ 이후 구간을 디코딩해 복원한다 (ObjectStorageService 가 URL 을 조립하는 곳이므로 역변환도
-- 그곳이 갖는다). 키 컬럼을 두면 URL 과 키가 항상 같이 움직여야 하는데, 키는 삭제에만 쓰이고
-- 조회 응답에는 URL 만 나가므로 동기화 대상만 하나 늘어난다.
--
-- 길이 512: OCI 네이티브 URL 은 region + namespace + bucket + 키(profile-images/yyyy/MM/{uuid}.png)
-- 를 합쳐 200자 안쪽이다. 버킷/네임스페이스 이름이 길어지는 경우까지 여유를 둔다.
ALTER TABLE users ADD COLUMN profile_image_url varchar(512);
