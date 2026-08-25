package com.example.mateon.common;

/**
 * 페이지 파라미터 정규화. 클라이언트가 보낸 page/size 를 그대로 믿지 않고 여기서 자른다.
 *
 * <p>
 * 원래 EventService 안의 private 메서드였는데, 북마크 목록도 같은 규칙을 써야 해서 꺼냈다.
 * 양쪽에 복붙해 두면 "size 상한은 100"이라는 값이 한쪽만 바뀌어 갈라진다 — 이건 서버 내부
 * 사정이 아니라 "배열 길이가 요청한 size 와 같으면 다음 페이지가 있다"로 프론트가 읽는
 * 공개 계약이라 한 곳에서만 정해져야 한다.
 */
public final class PageLimits {

    /**
     * 한 페이지 최대 건수. 목적이 과부하 방지이므로 클라이언트가 아무리 큰 size 를 보내도 여기서 자른다 —
     * 상한이 없으면 size=100000 한 방으로 전건 조회와 같아져 페이지네이션이 무의미해진다.
     */
    public static final int MAX_PAGE_SIZE = 100;

    private PageLimits() {
    }

    /**
     * 0-기반 페이지 번호. 음수는 0 으로 취급한다.
     */
    public static int clampPage(int page) {
        return Math.max(page, 0);
    }

    /**
     * 페이지당 건수. {@link #MAX_PAGE_SIZE} 로 상한을 두고, 1 미만은 1 로 올린다.
     */
    public static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
