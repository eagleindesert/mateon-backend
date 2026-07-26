package com.example.mateon.bookmarks.service;

import com.example.mateon.bookmarks.domain.EventBookmark;
import com.example.mateon.bookmarks.repository.EventBookmarkRepository;
import com.example.mateon.common.PageLimits;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 활동 북마크(즐겨찾기).
 *
 * <p><b>중복 요청을 에러로 돌려주지 않는다.</b> 이 레포는 보통 중복을 {@code DUPLICATE_RESOURCE} 로
 * 막지만(TeamOfferService), 그건 재제안 스팸을 막는 게 목적이라 그렇다. 북마크는 별 아이콘을
 * 두 번 누르는 게 정상 조작이고 네트워크 재시도로도 같은 요청이 두 번 올 수 있다. 이미 찜한 걸
 * 다시 등록하면 결과 상태는 어차피 '찜한 상태'로 같으므로, 실패시키는 대신 그 상태를 그대로
 * 알려준다. 해제도 마찬가지다 — 원래 없었든 방금 지웠든 결과는 '안 찜한 상태'다.
 *
 * <p>대신 DB 의 {@code uq_event_bookmarks_pair} 는 그대로 둔다. 멱등하게 응답하는 것과 행이
 * 두 개 생기는 것은 다른 얘기다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BookmarkService {

    private final EventBookmarkRepository bookmarkRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;

    /**
     * 활동을 북마크한다.
     *
     * @return 이번 호출로 새로 생겼으면 true, 이미 찜한 상태였으면 false.
     * 어느 쪽이든 끝난 뒤 상태는 '찜한 상태'다 (컨트롤러가 201/200 을 가르는 데만 쓴다).
     * @throws MateonException 활동이 없으면 {@link ErrorCode#EVENT_NOT_FOUND}
     */
    public boolean addBookmark(Long userId, Long eventId) {
        Event event = eventRepository.findById(eventId)
          .orElseThrow(() -> new MateonException(ErrorCode.EVENT_NOT_FOUND));

        if (bookmarkRepository.existsByUserIdAndEventId(userId, eventId)) {
            return false;
        }

        User user = userRepository.findById(userId)
          .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        try {
            bookmarkRepository.saveAndFlush(new EventBookmark(user, event));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 위 exists 검사와 INSERT 사이의 경합(더블클릭) → uq_event_bookmarks_pair 가 막아 준다.
            // 여기까지 왔다는 건 다른 요청이 먼저 넣었다는 뜻이고, 원하는 상태는 이미 만들어져 있다.
            return false;
        }
    }

    /**
     * 북마크를 해제한다.
     *
     * <p>활동이 존재하는지는 확인하지 않는다 — 없는 활동의 북마크는 애초에 생길 수 없고
     * (FK), 결과 상태는 어느 쪽이든 '안 찜한 상태'로 같다. 확인해 봐야 404 를 하나 더
     * 만들 뿐 프론트가 할 일은 달라지지 않는다.
     *
     * @return 이번 호출로 실제로 지워졌으면 true, 원래 없었으면 false
     */
    public boolean removeBookmark(Long userId, Long eventId) {
        return bookmarkRepository.deleteByUserIdAndEventId(userId, eventId) > 0;
    }

    /**
     * 내 북마크 목록. 활동 시작일순이 아니라 <b>북마크한 최신순</b>이다 — 방금 찜한 게 위에
     * 없으면 찜한 보람이 없다.
     *
     * <p>페이지 규약은 활동 검색과 같다(size 상한 {@link PageLimits#MAX_PAGE_SIZE},
     * 배열 길이가 요청한 size 와 같으면 다음 페이지 존재).
     */
    @Transactional(readOnly = true)
    public List<EventResponseDTO> getMyBookmarks(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(PageLimits.clampPage(page), PageLimits.clampSize(size));
        return bookmarkRepository.findBookmarkedEvents(userId, pageable).getContent().stream()
          // 북마크 목록이므로 전부 찜한 상태다. 다시 대조할 필요가 없다.
          .map(event -> new EventResponseDTO(event, true))
          .collect(Collectors.toList());
    }

    /**
     * 내가 찜한 활동 id 전량. 프론트가 대조표를 들고 여러 화면에서 별 아이콘을 칠할 때 쓴다.
     * id 만 나가므로 목록 API 와 달리 페이징하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Long> getMyBookmarkedEventIds(Long userId) {
        return bookmarkRepository.findAllBookmarkedEventIds(userId);
    }
}
