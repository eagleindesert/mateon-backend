package com.example.mateon.support;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * 단위 테스트에서 엔티티의 식별자를 강제로 채우는 도구.
 *
 * <p>
 * 이 레포의 관례는 "픽스처는 각 테스트 클래스의 private 헬퍼"다. 그런데 id 만은 예외로
 * 공용화한다 — {@code ChatMessage}, {@code ChatRoom}, {@code ChatRoomMember},
 * {@code Notification}, {@code MatchingIntentSession} 은 id 가 IDENTITY 생성이라 세터도 없고
 * 빌더 파라미터도 없다. 그래서 {@code new} 로 만든 순간 id 는 null 인데, 정작 응답 DTO 는
 * {@code message.getId()}, {@code message.getRoom().getId()} 를 읽는다.
 *
 * <p>
 * 필드명을 문자열로 넘기는 방식이라 이름을 틀리면 실패하는데, 그 실패가 여덟 군데에서
 * 똑같이 재현되는 걸 막으려고 한 곳에 모은다.
 *
 * <p>
 * 주의: 서비스가 {@code repository.save(...)} 의 반환값을 그대로 쓰는 경우, 여기서 id 를
 * 채운 <b>그 인스턴스</b>를 save 스텁이 되돌려주도록 해야 하류에서 id 가 보인다.
 */
public final class TestEntities {

    private TestEntities() {
    }

    /**
     * 엔티티의 {@code id} 필드에 값을 넣고 그 엔티티를 그대로 돌려준다 (체이닝용).
     */
    public static <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    /**
     * {@code id} 가 아닌 필드도 손봐야 할 때 (예: {@code @CreatedDate createdAt}).
     */
    public static <T> T withField(T entity, String fieldName, Object value) {
        ReflectionTestUtils.setField(entity, fieldName, value);
        return entity;
    }
}
