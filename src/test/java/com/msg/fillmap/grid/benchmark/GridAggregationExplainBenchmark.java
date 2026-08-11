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
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 뷰포트 집계 조회(MSG-356)의 최다 스캔 경로 EXPLAIN(ANALYZE, BUFFERS) 실측.
 * 상위 점령량 가정치(사용자 1인 1만 행)를 심고 전국 시야 시도 집계를 잰다 — 스펙 §성능이 요구한 확인이다.
 *
 * <p>일반 빌드에서 제외한다 — 환경변수 GRID_BENCHMARK=true 일 때만 실행한다.
 * <pre>GRID_BENCHMARK=true ./gradlew test --tests "*GridAggregationExplainBenchmark"</pre>
 *
 * <p>@Transactional 롤백이라 시드 데이터는 DB 에 남지 않는다(스키마·데이터 무변경 — 조회 실행만).
 * 출력 계획과 소요는 docs/spec/MSG-356.md 작업 로그에 기록한다.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "GRID_BENCHMARK", matches = "true")
@DisplayName("[벤치마크] 뷰포트 집계 EXPLAIN")
class GridAggregationExplainBenchmark {

	// 서해 공해상 기준점 — 실 격자·실 행정동과 겹치지 않는 블록에 합성 볼륨을 심는다.
	private static final double 공해상_LAT = 35.0;
	private static final double 공해상_LON = 125.0;

	// 1만 행 목표: 100 × 100 블록을 modulo 1(전부 점령)로 채운다.
	private static final int BLOCK_SIDE = 100;

	// 전국 시야 — 시도 단위 상한(10도) 안이면서 남한 전역을 덮는다.
	private static final ViewportBounds KOREA = new ViewportBounds(33.0, 124.0, 38.7, 131.0);

	@Autowired
	private EntityManager em;

	@Autowired
	private UserRepository userRepository;

	private long userId;

	@BeforeEach
	void seed() {
		userId = userRepository.save(User.createLocalUser("grid-agg-bench@example.com", "hash", "벤치")).getId();

		GridIndex base = GridEncoder.decode(GridEncoder.encode(공해상_LAT, 공해상_LON));
		long minY = base.gridY();
		long minX = base.gridX();
		long maxY = minY + BLOCK_SIDE - 1;
		long maxX = minX + BLOCK_SIDE - 1;

		int grids = GridFixtures.seedGridBlock(em, minY, maxY, minX, maxX);
		int mine = GridFixtures.seedUserGridBlock(em, userId, minY, maxY, minX, maxX, 1);
		em.flush();
		// EXPLAIN 계획이 현실 통계를 쓰도록 방금 삽입한 대량 데이터를 ANALYZE 한다.
		em.createNativeQuery("ANALYZE grids").executeUpdate();
		em.createNativeQuery("ANALYZE user_grids").executeUpdate();

		System.out.printf("%n[seed] grids=%d, user_grids(mine)=%d, block=[y %d..%d, x %d..%d]%n",
			grids, mine, minY, maxY, minX, maxX);
	}

	@Test
	@DisplayName("전국 시야 시도 집계의 실행계획을 출력한다")
	void 전국_시야_시도_집계의_실행계획을_출력한다() {
		GridEncoder.GridRange range = GridEncoder.viewportRange(KOREA);
		RegionUnit unit = RegionUnit.SIDO;

		String plan = explain("""
			SELECT
				substring(g.region_code FROM 1 FOR %d)     AS "regionCode",
				MIN(split_part(r.region_name, ' ', %d))    AS "name",
				AVG(ST_Y(g.center_geom::geometry))         AS "lat",
				AVG(ST_X(g.center_geom::geometry))         AS "lng",
				COUNT(*)                                   AS "count"
			FROM user_grids ug
			JOIN grids g ON g.grid_id = ug.grid_id
			LEFT JOIN regions r ON r.region_code = g.region_code
			WHERE ug.user_id = %d
				AND g.grid_y BETWEEN %d AND %d
				AND g.grid_x BETWEEN %d AND %d
			GROUP BY 1
			ORDER BY 1 NULLS LAST
			""".formatted(unit.getCodePrefixLength(), unit.getNameTokenIndex(), userId,
			range.minGridY(), range.maxGridY(), range.minGridX(), range.maxGridX()));

		System.out.println("\n================= 전국 시야 시도 집계 (사용자 1인 1만 행) =================");
		System.out.println(plan);
	}

	@SuppressWarnings("unchecked")
	private String explain(String sql) {
		List<String> lines = em.createNativeQuery("EXPLAIN (ANALYZE, BUFFERS, VERBOSE) " + sql).getResultList();
		return String.join("\n", lines);
	}
}
