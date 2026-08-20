package com.example.mateon.notification.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이름은 Repository 지만 DB 가 아니라 <b>프로세스 메모리</b> 저장소다.
 *
 * <p>
 * 여기서 확인할 값어치가 있는 건 하나뿐이다: <b>동시 접근에 안전해야 한다.</b>
 * 저장은 요청 스레드가, 삭제는 emitter 의 onCompletion/onTimeout 콜백 스레드가, 조회는
 * {@code @Async} 알림 푸시 스레드가 한다 — 셋이 동시에 들어온다. 평범한 {@code HashMap} 으로
 * 바꾸면 대부분의 상황에서 잘 돌다가 접속자가 늘었을 때 무한 루프나 값 유실로 나타난다.
 *
 * <p>
 * 같은 이유로, 이 저장소가 <b>프로세스 로컬</b>이라는 사실도 적어 둔다. 서버를 두 대로 늘리면
 * A 서버에 붙은 사용자에게 B 서버가 알림을 밀 수 없다 (DB 기록은 남으니 다음 조회 때 보인다).
 * 지금은 단일 인스턴스 전제라 문제가 없지만, 확장 시 가장 먼저 걸리는 곳이다.
 */
class EmitterRepositoryTest {

    private final EmitterRepository repository = new EmitterRepository();

    @Test
    @DisplayName("저장한 emitter 를 userId 로 되찾는다")
    void saveAndGet() {
        SseEmitter emitter = new SseEmitter();

        repository.save(1L, emitter);

        assertThat(repository.get(1L)).isSameAs(emitter);
    }

    @Test
    @DisplayName("구독한 적 없는 유저는 null 이다 — push 가 이 null 로 '접속 중 아님'을 판단한다")
    void unknownUserIsNull() {
        assertThat(repository.get(999L)).isNull();
    }

    @Test
    @DisplayName("같은 유저를 다시 저장하면 덮어쓴다 (재연결 시 옛 소켓이 남지 않는다)")
    void saveOverwrites() {
        SseEmitter first = new SseEmitter();
        SseEmitter second = new SseEmitter();

        repository.save(1L, first);
        repository.save(1L, second);

        assertThat(repository.get(1L)).isSameAs(second);
    }

    @Test
    @DisplayName("삭제하면 null 이 되고, 없는 것을 지워도 예외가 아니다")
    void deleteIsIdempotent() {
        repository.save(1L, new SseEmitter());

        repository.deleteById(1L);
        repository.deleteById(1L);

        assertThat(repository.get(1L)).isNull();
    }

    @Test
    @DisplayName("여러 스레드가 동시에 넣고 지워도 값이 유실되지 않는다 (ConcurrentHashMap 이어야 한다)")
    void isThreadSafe() throws Exception {
        int users = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(users);

        try {
            IntStream.range(0, users).forEach(i -> pool.execute(() -> {
                try {
                    repository.save((long) i, new SseEmitter());
                    // 짝수 유저는 곧바로 연결이 끊긴 상황을 흉내낸다.
                    if (i % 2 == 0) {
                        repository.deleteById((long) i);
                    }
                } finally {
                    done.countDown();
                }
            }));

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < users; i++) {
            if (i % 2 == 0) {
                assertThat(repository.get((long) i)).as("유저 %d 는 삭제됐어야 한다", i).isNull();
            } else {
                assertThat(repository.get((long) i)).as("유저 %d 는 남아 있어야 한다", i).isNotNull();
            }
        }
    }
}
