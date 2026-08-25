package com.example.mateon.user.service;

import com.example.mateon.common.storage.ObjectStorageService;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * 프로필 이미지의 버킷 작업을 요청 스레드 밖에서 처리한다.
 *
 * <p>
 * {@link ProfileImageService} 와 나눠 둔 이유는 두 가지다. {@code @Async} 는 프록시를 거쳐야
 * 동작하므로 같은 빈 안에서 호출하면 그냥 동기 실행된다. 그리고 트랜잭션 경계가 반대다 —
 * 접수 쪽은 요청 트랜잭션이 필요한 반면 여기는 <b>트랜잭션 없이</b> 돌아야 한다. 버킷 호출을
 * 트랜잭션에 묶으면 "객체 삭제 성공 → DB 반영" 순서가 커밋 시점에 휘둘리기 때문이다
 * (DB 갱신은 UserRepository 의 벌크 UPDATE 가 각자 자기 트랜잭션을 연다).
 *
 * <p>
 * 여기서 나는 실패는 응답으로 전할 길이 없다(사용자는 이미 200 을 받았다). 지금은 로그만
 * 남기고, 실패를 사용자에게 알리는 SSE 통보를 붙일 계획이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageWorker {

    private static final DateTimeFormatter KEY_PREFIX_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    private final UserRepository userRepository;
    private final ObjectStorageService objectStorageService;

    /**
     * 프로필 이미지를 교체한다. <b>이전 객체 삭제와 새 객체 업로드가 한 작업</b>이며 순서를 지킨다.
     *
     * <p>
     * 삭제를 먼저 하는 이유: 버킷에 남는 고아 객체를 만들지 않는다. 삭제가 실패하면 업로드도
     * 하지 않고 멈춘다 — 그래야 사용자에게는 이전 사진이 그대로 보이는 일관된 상태로 남고,
     * 지우지 못한 객체가 계속 참조되지 않은 채 쌓이는 일도 없다.
     *
     * <p>
     * 예외를 밖으로 던지지 않는다. {@code @Async void} 는 던져도 받는 곳이 없다.
     */
    @Async
    public void replaceProfileImage(Long userId, byte[] bytes, String extension, String contentType) {
        // 접수 시점에 확인했지만 다시 읽는다. 이전 URL 은 그 사이 바뀔 수 있고(연속 업로드),
        // 유저가 탈퇴했으면 올릴 자리도 없다.
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            log.warn("프로필 이미지 교체를 중단합니다 — 유저가 없습니다: userId={}", userId);
            return;
        }

        String oldUrl = found.get().getProfileImageUrl();
        if (oldUrl != null && !deletePreviousObject(userId, oldUrl)) {
            return;
        }

        String key = "profile-images/%s/%s.%s".formatted(
          YearMonth.now().format(KEY_PREFIX_FORMAT), UUID.randomUUID(), extension);
        try {
            String newUrl = objectStorageService.upload(key, bytes, contentType);
            userRepository.updateProfileImageUrl(userId, newUrl, LocalDateTime.now());
        } catch (Exception e) {
            // 이전 사진은 이미 지웠으므로 사용자는 사진 없는 상태가 된다. 되돌릴 수단이 없어
            // (지운 객체는 복구되지 않는다) 그대로 두고 다시 올리게 안내해야 한다.
            // TODO: 업로드 실패를 SSE 로 통보한다.
            log.error("프로필 이미지 업로드 실패: userId={}, key={}", userId, key, e);
        }
    }

    /**
     * 이전 객체를 지우고, 성공했으면 DB 의 URL 도 즉시 비운다.
     *
     * <p>
     * URL 을 바로 비우는 이유: 지워진 객체를 가리키는 URL 이 조회 응답에 남아 있으면 프론트가
     * 깨진 이미지를 그린다. 업로드가 끝날 때까지 기다리지 않고 먼저 끊는다.
     *
     * @return 삭제 성공 여부. false 면 교체 작업 전체를 중단해야 한다.
     */
    private boolean deletePreviousObject(Long userId, String oldUrl) {
        try {
            objectStorageService.delete(oldUrl);
        } catch (Exception e) {
            // TODO: 이전 객체 삭제 실패를 SSE 로 통보한다.
            log.error("이전 프로필 이미지 삭제 실패 — 교체를 중단합니다: userId={}, url={}", userId, oldUrl, e);
            return false;
        }
        userRepository.clearProfileImageUrlIfMatches(userId, oldUrl, LocalDateTime.now());
        return true;
    }

    /**
     * 프로필 이미지를 없앤다 (DELETE 엔드포인트). <b>버킷 객체를 지운 뒤에만</b> URL 을 비운다.
     *
     * <p>
     * 순서를 이렇게 잡은 이유: DB 를 먼저 비우면 삭제가 실패했을 때 아무도 참조하지 않는 객체가
     * 버킷에 영구히 남는다. 반대로 객체를 먼저 지우면, DB 반영에 실패해도 URL 하나가 깨진 링크로
     * 남을 뿐이고 다음 업로드나 삭제 시도에 정리된다.
     */
    @Async
    public void deleteObjectThenClearUrl(Long userId, String oldUrl) {
        try {
            objectStorageService.delete(oldUrl);
        } catch (Exception e) {
            // DB 는 건드리지 않았으므로 사용자에게는 사진이 그대로 보인다(일관 상태). 다시 삭제를
            // 누르면 재시도된다.
            // TODO: 삭제 실패를 SSE 로 통보한다.
            log.error("프로필 이미지 삭제 실패: userId={}, url={}", userId, oldUrl, e);
            return;
        }

        int cleared = userRepository.clearProfileImageUrlIfMatches(userId, oldUrl, LocalDateTime.now());
        if (cleared == 0) {
            // 삭제를 처리하는 동안 새 사진이 올라온 경우다. 그 URL 을 비우면 방금 올린 사진이
            // 사라지므로 건드리지 않는다. 지금 지운 객체는 아무도 참조하지 않는다.
            log.info("프로필 이미지 URL 이 이미 바뀌어 초기화를 건너뜁니다: userId={}, url={}", userId, oldUrl);
        }
    }
}
