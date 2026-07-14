package com.msg.fillmap.grid.benchmark;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * viewport 전략 A(정수 범위 스캔) vs B(GIST 공간 쿼리) EXPLAIN(ANALYZE, BUFFERS) 벤치마크.
 *
 * <p>일반 빌드에서 제외한다 — 환경변수 GRID_BENCHMARK=true 일 때만 실행한다.
 * <pre>GRID_BENCHMARK=true ./gradlew test --tests "com.msg.fillmap.grid.benchmark.GridViewportExplainBenchmark"</pre>
 *
 * <p>@Transactional 롤백이라 시드 데이터는 DB 에 남지 않는다(스키마·데이터 무변경 — EXPLAIN 실행만).
 * 출력 계획을 docs/MSG-73.md 작업 로그에 기록한다. 최종 채택은 MSG-128 관측 하 k6 부하테스트 후 결정(보류).
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "GRID_BENCHMARK", matches = "true")
@DisplayName("[벤치마크] viewport 전략 A/B EXPLAIN")
class GridViewportExplainBenchmark {

	// 서울 대략 경계를 격자 인덱스 블록으로 환산해 시드한다(현실적 볼륨).
	private static final double SEOUL_MIN_LAT = 37.42;
	private static final double SEOUL_MAX_LAT = 37.70;
	private static final double SEOUL_MIN_LON = 126.76;
	private static final double SEOUL_MAX_LON = 127.18;

	// 점령 밀도 — (yy + xx) % modulo == 0 셀만 내 점령으로 시드(약 1/3).
	private static final int OCCUPY_MODULO = 3;

	// 대표 뷰포트: 지도 한 화면(약 4.4km × 5.6km).
	private static final double VIEW_SPAN_LAT = 0.04;
	private static final double VIEW_SPAN_LON = 0.05;

	@Autowired
	private EntityManager em;

	@Autowired
	private UserRepository userRepository;

	private long userId;
	private long minY;
	private long maxY;
	private long minX;
	private long maxX;

	@BeforeEach
	void seed() {
		userId = userRepository.save(User.createLocalUser("grid-bench@example.com", "hash", "벤치")).getId();

		GridIndex sw = GridEncoder.decode(GridEncoder.encode(SEOUL_MIN_LAT, SEOUL_MIN_LON));
		GridIndex ne = GridEncoder.decode(GridEncoder.encode(SEOUL_MAX_LAT, SEOUL_MAX_LON));
		minY = sw.gridY();
		maxY = ne.gridY();
		minX = sw.gridX();
		maxX = ne.gridX();

		int grids = GridFixtures.seedGridBlock(em, minY, maxY, minX, maxX);
		int mine = GridFixtures.seedUserGridBlock(em, userId, minY, maxY, minX, maxX, OCCUPY_MODULO);
		em.flush();
		// EXPLAIN 계획이 현실 통계를 쓰도록 방금 삽입한 대량 데이터를 ANALYZE 한다.
		em.createNativeQuery("ANALYZE grids").executeUpdate();
		em.createNativeQuery("ANALYZE user_grids").executeUpdate();

		System.out.printf("%n[seed] grids=%d, user_grids(mine)=%d, block=[y %d..%d, x %d..%d]%n",
			grids, mine, minY, maxY, minX, maxX);
	}

	@Test
	@DisplayName("접근 A·B 각각 EXPLAIN(ANALYZE, BUFFERS) 를 출력한다")
	void 접근_A와_B의_실행계획을_출력한다() {
		// 서울 중심 근처의 대표 뷰포트 하나.
		double swLat = (SEOUL_MIN_LAT + SEOUL_MAX_LAT) / 2;
		double swLng = (SEOUL_MIN_LON + SEOUL_MAX_LON) / 2;
		double neLat = swLat + VIEW_SPAN_LAT;
		double neLng = swLng + VIEW_SPAN_LON;

		GridIndex sw = GridEncoder.decode(GridEncoder.encode(swLat, swLng));
		GridIndex ne = GridEncoder.decode(GridEncoder.encode(neLat, neLng));

		String planA = explain("""
			SELECT g.grid_id, g.grid_y, g.grid_x
			FROM user_grids ug
			JOIN grids g ON g.grid_id = ug.grid_id
			WHERE ug.user_id = %d
				AND g.grid_y BETWEEN %d AND %d
				AND g.grid_x BETWEEN %d AND %d
			""".formatted(userId, sw.gridY(), ne.gridY(), sw.gridX(), ne.gridX()));

		String planB = explain("""
			SELECT g.grid_id, g.grid_y, g.grid_x
			FROM user_grids ug
			JOIN grids g ON g.grid_id = ug.grid_id
			WHERE ug.user_id = %d
				AND ST_Intersects(
					g.bbox_geom,
					ST_MakeEnvelope(%.6f, %.6f, %.6f, %.6f, 4326)::geography
				)
			""".formatted(userId, swLng, swLat, neLng, neLat));

		System.out.println("\n================= 접근 A: 정수 범위 스캔 (btree) =================");
		System.out.println(planA);
		System.out.println("\n================= 접근 B: GIST 공간 쿼리 (GiST) =================");
		System.out.println(planB);
	}

	@SuppressWarnings("unchecked")
	private String explain(String sql) {
		List<String> lines = em.createNativeQuery("EXPLAIN (ANALYZE, BUFFERS, VERBOSE) " + sql).getResultList();
		return String.join("\n", lines);
	}
}
