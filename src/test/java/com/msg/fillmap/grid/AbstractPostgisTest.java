package com.msg.fillmap.grid;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostGIS Testcontainers 공용 베이스 (MSG-68 D6).
 * <p>
 * 싱글턴 컨테이너 — JVM당 한 번만 띄우고 모든 테스트 클래스가 공유한다. {@code @Container}의 클래스별
 * start/stop 생명주기를 쓰면 클래스 간 캐시되는 {@code @DataJpaTest} 컨텍스트가 이미 내려간 컨테이너
 * 포트를 가리켜 연결이 거부된다. Ryuk이 JVM 종료 시 컨테이너를 정리한다.
 * <p>
 * Flyway가 V1__init.sql을 실제로 실행하므로 스키마와 native 쿼리를 함께 검증한다.
 * CI(ci.yml)와 동일한 alpine 이미지 — arm64 네이티브라 애플 실리콘 에뮬레이션을 피한다.
 * MSG-69 경합 테스트, MSG-73 뷰포트 공간 쿼리가 그대로 재사용한다.
 */
@DataJpaTest
public abstract class AbstractPostgisTest {

	@ServiceConnection
	static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
		DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

	static {
		POSTGIS.start();
	}
}
