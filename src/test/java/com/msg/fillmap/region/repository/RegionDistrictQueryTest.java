package com.msg.fillmap.region.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.squareKm2Json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegionRepository.findDistricts — 시군구 목록 native (MSG-435 모듈 1, 실 PostGIS).
 * 격리: 실존하지 않는 sido 99 대역 중에서도 이 테스트 전용 parent_code 9994x 합성 행정동 + @Transactional 롤백.
 * 전국 목록이라 공유 DB 의 실데이터가 함께 나오므로, 단정은 9994x 대역으로 걸러 낸 부분집합에만 건다.
 */
@SpringBootTest
@Transactional
@DisplayName("RegionRepository.findDistricts — 시군구 목록 (합성 row·롤백 격리)")
class RegionDistrictQueryTest {

	/** 이 테스트 전용 시군구 코드 접두 — 다른 region 테스트(999 0x 대역)와도 겹치지 않는다. */
	private static final String PARENT_PREFIX = "9994";

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	/** 합성 행정동 1건. 조회가 geospatial 을 안 타므로 폴리곤은 유효하기만 하면 된다. */
	private void seedDong(String parentCode, String suffix, String sigunguName) {
		regionRepository.upsert(parentCode + suffix, "합성시 " + sigunguName + " 합성동" + suffix,
			parentCode, squareKm2Json(1.0), CELL_AREA_M2);
	}

	private void setGridCount(String regionCode, int count) {
		em.createNativeQuery("UPDATE regions SET total_grid_count = :n WHERE region_code = :c")
			.setParameter("n", count).setParameter("c", regionCode).executeUpdate();
	}

	private long totalGridCount(String regionCode) {
		Number n = (Number) em.createNativeQuery("SELECT total_grid_count FROM regions WHERE region_code = :c")
			.setParameter("c", regionCode).getSingleResult();
		return n.longValue();
	}

	/** 이 테스트가 심은 시군구만. 공유 DB 실데이터를 배제하되 그들 사이의 상대 순서는 보존된다. */
	private List<RegionDistrictProjection> synthetic() {
		return regionRepository.findDistricts().stream()
			.filter(d -> d.getParentCode().startsWith(PARENT_PREFIX))
			.toList();
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("시군구 격자 수는 산하 행정동 격자 수의 합이다")
	void 시군구_격자_수는_산하_행정동_격자_수의_합이다() {
		seedDong("99940", "00001", "합성구");
		seedDong("99940", "00002", "합성구");
		seedDong("99940", "00003", "합성구");

		List<RegionDistrictProjection> districts = synthetic();

		long expected = totalGridCount("9994000001") + totalGridCount("9994000002") + totalGridCount("9994000003");
		assertThat(districts).hasSize(1);
		assertThat(districts.get(0).getParentCode()).isEqualTo("99940");
		assertThat(districts.get(0).getName()).isEqualTo("합성구");
		assertThat(districts.get(0).getGridCount()).isEqualTo(expected);
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("격자 수가 0 인 시군구는 목록에서 빠진다")
	void 격자_수가_0인_시군구는_목록에서_빠진다() {
		seedDong("99945", "00001", "빈구");
		seedDong("99945", "00002", "빈구");
		setGridCount("9994500001", 0);
		setGridCount("9994500002", 0);
		seedDong("99946", "00001", "있는구");

		assertThat(synthetic())
			.extracting(RegionDistrictProjection::getParentCode)
			.containsExactly("99946");
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("목록은 이름순이고 같은 이름은 코드순이다")
	void 목록은_이름순이고_같은_이름은_코드순이다() {
		// 심는 순서와 코드 순서를 일부러 어긋내, 정렬이 삽입 순서가 아니라 이름·코드로 결정되는지 본다.
		seedDong("99943", "00001", "하중구");
		seedDong("99941", "00001", "가중구");
		seedDong("99942", "00001", "하중구");

		assertThat(synthetic())
			.extracting(RegionDistrictProjection::getParentCode)
			.containsExactly("99941", "99942", "99943");
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("한 시군구에 다른 이름이 섞이면 코드포인트가 앞선 이름이 뽑힌다")
	void 한_시군구에_다른_이름이_섞이면_코드포인트가_앞선_이름이_뽑힌다() {
		// 실데이터는 같은 parent_code 면 둘째 토큰이 한 값이지만, 섞였을 때의 선택이 DB 로케일에 좌우되면
		// 환경마다 이름이 달라진다 — MIN 입력의 COLLATE "C" 가 코드포인트 순으로 고정한다(§D-4).
		// 'Z'(0x5A) < 'a'(0x61) 라 코드포인트 순이면 Z 쪽, 로케일 정렬(en_US 등)이면 a 쪽이 뽑힌다.
		seedDong("99944", "00001", "a합성구");
		seedDong("99944", "00002", "Z합성구");

		assertThat(synthetic())
			.extracting(RegionDistrictProjection::getName)
			.containsExactly("Z합성구");
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("parent_code 가 없는 행은 목록에 들어가지 않는다")
	void parent_code가_없는_행은_목록에_들어가지_않는다() {
		regionRepository.upsert("9994900001", "합성시 무주구 합성동", null, squareKm2Json(1.0), CELL_AREA_M2);

		assertThat(regionRepository.findDistricts())
			.extracting(RegionDistrictProjection::getParentCode)
			.doesNotContainNull();
		assertThat(synthetic()).isEmpty();
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("실행 쿼리에 공간 연산이 없다")
	void 실행_쿼리에_공간_연산이_없다() throws Exception {
		Method findDistricts = RegionRepository.class.getMethod("findDistricts");
		String sql = findDistricts.getAnnotation(Query.class).value();

		assertThat(sql)
			.doesNotContain("ST_")
			.doesNotContain("user_id")
			.contains("FROM regions")
			.contains("HAVING SUM(total_grid_count) > 0")
			.contains("COLLATE \"C\"");
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("전국 규모 실행 계획이 성능 기준을 지킨다")
	void 전국_규모_실행_계획이_성능_기준을_지킨다() throws Exception {
		long rows = ((Number) em.createNativeQuery("SELECT count(*) FROM regions").getSingleResult()).longValue();
		// D-5 기준이 "전국 3,558행 기준"이라, 전국 시드가 없는 DB 에서는 재도 의미가 없어 skip 한다
		// (안 걸면 빈 테이블을 재고 통과하는 허수 단언이 된다).
		assumeTrue(rows >= 3500, "전국 regions 시드(3,558행)가 있는 환경에서만 성능 기준을 판정한다. 현재 " + rows + "행");
		String plan = explainAnalyze();
		double executionMs = Double.parseDouble(plan.replaceAll("(?s).*\"Execution Time\": ([0-9.]+).*", "$1"));
		System.out.printf("[MSG-435] regions %d행 · Execution Time %.3f ms%n%s%n", rows, executionMs, plan);

		// D-5 합격 기준: 50ms 이내 + regions Seq Scan 1회 허용(전행 집계라 스캔이 정상 계획).
		assertThat(executionMs).isLessThan(50.0);
		assertThat(plan.split("\"Node Type\": \"Seq Scan\"", -1).length - 1).isLessThanOrEqualTo(1);
	}

	/** 실서비스와 같은 문장을 재는지 보장하려고 SQL 을 복사하지 않고 @Query 원문에 EXPLAIN 만 씌운다. */
	private String explainAnalyze() throws Exception {
		String sql = RegionRepository.class.getMethod("findDistricts").getAnnotation(Query.class).value();
		return String.valueOf(em.createNativeQuery("EXPLAIN (ANALYZE, FORMAT JSON) " + sql).getSingleResult());
	}
}
