package com.msg.fillmap.zone.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.zone.repository.ZoneRepository;

/**
 * ZoneSeeder 게이트·멱등성 (실 PostgreSQL) — 합성 zone_key·롤백 격리 (MSG-234 §D7, MSG-154 선례).
 * 데이터는 ByteArrayResource(합성 JSON)로 주입하고, region_code 는 FK 충돌을 피해 null 로 둔다.
 */
@SpringBootTest
@Transactional
@DisplayName("ZoneSeeder 게이트·멱등 (실 PostgreSQL) — 합성 zone_key·롤백 격리")
class ZoneSeederTest {

	@Autowired
	private ZoneRepository zoneRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	private ZoneSeeder newSeeder(boolean enabled, String path) {
		ZoneSeeder seeder = new ZoneSeeder(zoneRepository, objectMapper);
		ReflectionTestUtils.setField(seeder, "enabled", enabled);
		ReflectionTestUtils.setField(seeder, "path", path);
		return seeder;
	}

	private Resource json(String zoneKey, String name, int minY, int maxY, int minX, int maxX, int priority) {
		String content = """
			[{"zoneKey":"%s","name":"%s","regionCode":null,\
			"minGridY":%d,"maxGridY":%d,"minGridX":%d,"maxGridX":%d,"priority":%d}]"""
			.formatted(zoneKey, name, minY, maxY, minX, maxX, priority);
		return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
	}

	private long countKey(String zoneKey) {
		em.flush();
		Number n = (Number) em.createNativeQuery("SELECT COUNT(*) FROM zones WHERE zone_key = :k")
			.setParameter("k", zoneKey).getSingleResult();
		return n.longValue();
	}

	private Object scalar(String column, String zoneKey) {
		em.flush();
		return em.createNativeQuery("SELECT " + column + " FROM zones WHERE zone_key = :k")
			.setParameter("k", zoneKey).getSingleResult();
	}

	@Test
	@DisplayName("seed_enabled 가 off 면 시더가 동작하지 않는다 (게이트 — 없는 경로여도 읽지 않아 무예외)")
	void seed_enabled가_off면_시더가_동작하지_않는다() {
		ZoneSeeder off = newSeeder(false, "seed/does-not-exist.json");

		// 게이트가 off 면 리소스를 아예 읽지 않으므로 없는 경로여도 예외가 나지 않는다(가드가 없으면 IllegalState).
		assertThatCode(() -> off.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("seed_enabled 가 on 이면 run 이 클래스패스 데이터 파일을 읽는다 (기본 zones.json = 빈 배열, 0건 무예외 §D7)")
	void seed_enabled가_on이면_run이_클래스패스_데이터_파일을_읽는다() {
		ZoneSeeder on = newSeeder(true, "seed/zones.json"); // 실제 동봉 파일 — 빈 배열이라 DB 무접점

		assertThatCode(() -> on.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("seed_enabled 가 on 이면 데이터 파일의 zone 을 적재한다 (JSON 로드)")
	void seed_enabled가_on이면_데이터_파일의_zone을_적재한다() {
		ZoneSeeder seeder = newSeeder(true, "unused");

		int loaded = seeder.seed(json("m234-load", "m234적재구역", 45100, 45110, 112200, 112220, 0));

		assertThat(loaded).isEqualTo(1);
		assertThat(countKey("m234-load")).isEqualTo(1L);
		assertThat(scalar("name", "m234-load")).isEqualTo("m234적재구역");
	}

	@Test
	@DisplayName("시더를 두 번 돌려도 zone 이 중복되지 않는다 (멱등)")
	void 시더를_두번_돌려도_zone이_중복되지_않는다() {
		ZoneSeeder seeder = newSeeder(true, "unused");

		seeder.seed(json("m234-idem", "m234멱등구역", 45100, 45110, 112200, 112220, 0));
		seeder.seed(json("m234-idem", "m234멱등구역", 45100, 45110, 112200, 112220, 0));

		assertThat(countKey("m234-idem")).isEqualTo(1L);
	}

	@Test
	@DisplayName("같은 zone_key 로 이름이나 사각형을 바꿔 재시딩하면 기존 행이 갱신된다 (ON CONFLICT DO UPDATE 수렴)")
	void 같은_zone_key로_이름이나_사각형을_바꿔_재시딩하면_기존_행이_갱신된다() {
		ZoneSeeder seeder = newSeeder(true, "unused");

		seeder.seed(json("m234-upd", "m234옛이름", 45100, 45110, 112200, 112220, 0));
		seeder.seed(json("m234-upd", "m234새이름", 45200, 45205, 112300, 112310, 7));

		assertThat(countKey("m234-upd")).isEqualTo(1L);                             // 중복 아님
		assertThat(scalar("name", "m234-upd")).isEqualTo("m234새이름");             // 이름 갱신
		assertThat(((Number) scalar("min_grid_y", "m234-upd")).intValue()).isEqualTo(45200); // 사각형 갱신
		assertThat(((Number) scalar("priority", "m234-upd")).intValue()).isEqualTo(7);
	}
}
