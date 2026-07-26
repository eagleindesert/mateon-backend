package com.example.mateon.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DB 를 실제로 쓰는 통합 테스트의 공통 바탕.
 *
 * <p>
 * 테스트는 개발 DB(localhost:5432)에 붙지 않는다. 붙으면 크롤러가 활동을 더 넣는 것만으로
 * 테스트가 깨지고(정렬·페이징 결과가 밀린다), 그 DB 가 없는 CI 에서는 아예 돌지 않는다.
 * 대신 실행할 때마다 빈 Postgres 컨테이너를 띄우고 Flyway 가 V1 부터 스키마를 새로 만든다.
 * 그래서 각 테스트는 자기가 심은 데이터만 있는 DB 를 보고, 검색 결과 전건을 그대로 단정할 수 있다.
 *
 * <p>
 * 이미지는 docker-compose 와 같은 pgvector 빌드를 쓴다 — 임베딩 테이블(V6)과 트라이그램
 * 인덱스(V23)가 확장에 의존하고, 정렬(NULLS LAST)·RANDOM() 도 Postgres 동작 그대로 검증해야 한다.
 *
 * <p>
 * 컨테이너는 static 블록에서 한 번만 띄워 JVM 안의 모든 테스트가 공유한다(클래스마다 띄우면
 * 기동 시간이 그만큼 곱해진다). 테스트 간 격리는 {@code @Transactional} 롤백이 맡고,
 * JVM 이 끝나면 Testcontainers 가 컨테이너를 지운다.
 */
@SpringBootTest
@Transactional
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }
}
