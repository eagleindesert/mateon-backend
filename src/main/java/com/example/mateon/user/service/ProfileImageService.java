package com.example.mateon.user.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.storage.ImageFileValidator;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 프로필 이미지 요청 접수. 버킷 작업은 {@link ProfileImageWorker} 가 비동기로 처리하므로
 * 여기서는 <b>즉시 거절할 수 있는 것만</b> 확인하고 넘긴다.
 *
 * <p>그래서 응답에 이미지 URL 이 없다. URL 은 작업이 끝난 뒤 유저 조회 3종
 * (GET /api/users/me, /mypage, /{userId}) 으로 확인한다 — 프론트에는 갱신이 조금 늦게
 * 보이는 구간이 있다.
 *
 * <p>클래스에 {@code @Transactional} 을 걸지 않는다. 여기서 하는 DB 작업은 유저 존재 확인뿐이고,
 * 트랜잭션을 열면 워커 트리거가 커밋 전에 실행되어 워커가 옛 데이터를 읽을 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileImageService {

    /**
     * 프로필 사진 한도. 공모전 포스터와 값이 같지만 근거가 다르다(그쪽은 AI 서버 제한).
     * ErrorCode.IMAGE_TOO_LARGE 문구가 "10MB 이하"로 고정돼 있어, 바꾸려면 문구도 함께 손봐야 한다.
     */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final UserRepository userRepository;
    private final ProfileImageWorker profileImageWorker;

    /**
     * 프로필 이미지 업로드를 접수한다. 실제 교체(이전 객체 삭제 → 새 객체 업로드)는 비동기다.
     *
     * <p>형식·크기 검증과 파일 읽기는 여기서 동기로 한다. 잘못된 파일은 400 으로 바로 알려야
     * 사용자가 다시 고를 수 있고, {@link MultipartFile} 의 임시 파일은 응답 후 정리되므로
     * 바이트도 요청 스레드에서 읽어 둬야 한다.
     *
     * @throws MateonException INVALID_IMAGE_FILE(400) / IMAGE_TOO_LARGE(413) — 업로드 파일 문제,
     *                         USER_NOT_FOUND(404)
     */
    public void upload(Long userId, MultipartFile image) {
        ImageFileValidator.ValidatedImage validated = ImageFileValidator.validate(image, MAX_IMAGE_BYTES);
        if (!userRepository.existsById(userId)) {
            throw new MateonException(ErrorCode.USER_NOT_FOUND);
        }

        profileImageWorker.replaceProfileImage(
                userId, validated.bytes(), validated.extension(), validated.contentType());
    }

    /**
     * 프로필 이미지 삭제를 접수한다. 실제 삭제(버킷 객체 → DB URL 순서)는 비동기다.
     *
     * <p>이미지가 없으면 아무것도 하지 않고 성공으로 끝낸다 — 지울 게 없을 뿐, 요청이 원한
     * 결과 상태("사진 없음")는 이미 만족돼 있다.
     *
     * @throws MateonException USER_NOT_FOUND(404)
     */
    public void delete(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        String currentUrl = user.getProfileImageUrl();
        if (currentUrl == null) {
            return;
        }
        profileImageWorker.deleteObjectThenClearUrl(userId, currentUrl);
    }
}
