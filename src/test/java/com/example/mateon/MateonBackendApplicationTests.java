package com.example.mateon;

import com.example.mateon.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 애플리케이션 컨텍스트가 뜨는지만 본다.
 * DB 는 {@link IntegrationTestBase} 의 컨테이너를 쓴다 — 개발 DB 없이도(=CI 에서도) 떠야 한다.
 */
class MateonBackendApplicationTests extends IntegrationTestBase {

    @Test
    @DisplayName("애플리케이션 컨텍스트가 뜬다 (모든 빈의 설정값이 test 프로필만으로 채워진다)")
    void contextLoads() {
    }

}
