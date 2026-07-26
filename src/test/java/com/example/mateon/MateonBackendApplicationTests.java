package com.example.mateon;

import com.example.mateon.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * 애플리케이션 컨텍스트가 뜨는지만 본다.
 * DB 는 {@link IntegrationTestBase} 의 컨테이너를 쓴다 — 개발 DB 없이도(=CI 에서도) 떠야 한다.
 */
class MateonBackendApplicationTests extends IntegrationTestBase {

	@Test
	void contextLoads() {
	}

}
