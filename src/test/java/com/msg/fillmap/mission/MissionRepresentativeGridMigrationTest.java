package com.msg.fillmap.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import com.msg.fillmap.global.geo.AreaCell;
import com.msg.fillmap.global.geo.RepresentativeGridResolver;

/**
 * V42 대표 격자 마이그레이션 검증 (MSG-459, 실 PostgreSQL). 컨텍스트가 뜨는 것 자체가 V42 적용 증거이고,
 * 그 위에서 백필 SQL·CHECK·지연 FK 를 합성 행으로 확인한다.
 * <p>
 * 백필 대조는 마이그레이션 파일의 UPDATE 문을 <b>그 파일에서 읽어</b> 실행한다 — 테스트에 SQL 을 옮겨
 * 적으면 사본이 갈라져도 테스트가 통과하기 때문이다. 이 티켓이 고정하려는 것이 정확히 "백필 SQL 과
 * 리졸버가 같은 답을 낸다"(스펙 D-2)이므로 대조의 한쪽은 실제 문장이어야 한다.
 * <p>
 * 격리(공유 로컬 DB): grids 에 실재할 수 없는 999900 대역 논리 격자와 {@code @Transactional} 롤백만
 * 쓴다(MissionSchemaMigrationTest 선례) — truncate 하지 않는다. 백필 UPDATE 를 다시 돌려도 실데이터는
 * 이미 같은 값으로 채워져 있어 무변경이고, 어차피 롤백된다.
 */
@SpringBootTest
@Transactional
@DisplayName("V42 미션 대표 격자 마이그레이션 (실 PostgreSQL · 롤백 격리)")
class MissionRepresentativeGridMigrationTest {

	/** 합성 격자 대역 — 실데이터·타 테스트와 겹치지 않는 논리 인덱스. */
	private static final long BASE_Y = 999900;
	private static final long BASE_X = 999800;

	@Autowired
	private EntityManager em;

	/**
	 * V42 의 백필 UPDATE 문 원문. 파일에서 잘라 쓰는 이유는 위 클래스 주석에 적었다 — 세미콜론까지가
	 * 한 문장이고 이 파일에 UPDATE 는 하나뿐이라 첫 매치로 잘린다.
	 */
	private static String backfillSql() {
		String script;
		try {
			script = StreamUtils.copyToString(
				new ClassPathResource("db/migration/V42__mission_representative_grid.sql").getInputStream(),
				StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		int start = script.indexOf("UPDATE missions m");
		int end = script.indexOf(';', start);
		return script.substring(start, end);
	}

	private long 미션(String type, int targetCount) {
		return ((Number) em.createNativeQuery(
			"INSERT INTO missions (type, title, target_count) VALUES (:t, 'msg459 합성미션', :c) RETURNING id")
			.setParameter("t", type).setParameter("c", targetCount).getSingleResult()).longValue();
	}

	/** 미션 판정 격자 집합을 직사각형으로 심는다 — 행·열은 칸 수, 원점은 999900 대역이다. */
	private Set<AreaCell> 격자블록(long missionId, int rows, int columns) {
		Set<AreaCell> cells = new LinkedHashSet<>();
		for (int dy = 0; dy < rows; dy++) {
			for (int dx = 0; dx < columns; dx++) {
				cells.add(new AreaCell((int) (BASE_Y + dy), (int) (BASE_X + dx)));
			}
		}
		cells.forEach(cell -> 격자(missionId, cell.gridId()));
		return cells;
	}

	private void 격자(long missionId, String gridId) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:m, :g)")
			.setParameter("m", missionId).setParameter("g", gridId).executeUpdate();
	}

	private void 백필실행() {
		em.createNativeQuery(backfillSql()).executeUpdate();
	}

	private String 대표격자(long missionId) {
		return (String) em.createNativeQuery(
			"SELECT representative_grid_id FROM missions WHERE id = :m")
			.setParameter("m", missionId).getSingleResult();
	}

	private void 대표격자지정(long missionId, String gridId) {
		em.createNativeQuery("UPDATE missions SET representative_grid_id = :g WHERE id = :m")
			.setParameter("g", gridId).setParameter("m", missionId).executeUpdate();
	}

	/** 지연 제약을 그 자리에서 검사시킨다 — 커밋을 기다리지 않고 위반을 관측하는 표준 방법이다. */
	private void 제약즉시검사() {
		em.createNativeQuery("SET CONSTRAINTS ALL IMMEDIATE").executeUpdate();
	}

	@Nested
	@DisplayName("백필 (스펙 D-2 — 증명 가능한 형태만)")
	class 백필 {

		@Test
		@DisplayName("백필_SQL_결과가_리졸버_산출값과_같다")
		void 백필_SQL_결과가_리졸버_산출값과_같다() {
			// 축제 81칸(9×9)과 팝업 1·2·4칸 — 실데이터에 존재하는 형태 전부다.
			record 사례(String type, int rows, int columns) {
			}
			List<사례> 사례들 = List.of(new 사례("EVENT", 9, 9), new 사례("POPUP", 1, 1),
				new 사례("POPUP", 1, 2), new 사례("POPUP", 2, 2));

			for (사례 하나 : 사례들) {
				long missionId = 미션(하나.type(), 1);
				Set<AreaCell> cells = 격자블록(missionId, 하나.rows(), 하나.columns());

				백필실행();

				assertThat(대표격자(missionId))
					.as("%d×%d %s", 하나.rows(), 하나.columns(), 하나.type())
					.isEqualTo(RepresentativeGridResolver.resolve(cells, null));
			}
		}

		@Test
		@DisplayName("직사각형이_아닌_레거시_팝업_블록은_대표_격자가_NULL로_남는다")
		void 직사각형이_아닌_레거시_팝업_블록은_대표_격자가_NULL로_남는다() {
			// V28 격자 재계산으로 한 칸이 떨어져 나간 3×3 - 1 = 8칸. HAVING 의 "꽉 찬 직사각형" 조건이 막는다.
			long missionId = 미션("POPUP", 1);
			격자블록(missionId, 3, 3);
			em.createNativeQuery("DELETE FROM mission_grids WHERE mission_id = :m AND grid_id = :g")
				.setParameter("m", missionId).setParameter("g", (BASE_Y + 2) + "_" + (BASE_X + 2)).executeUpdate();

			백필실행();

			assertThat(대표격자(missionId)).isNull();
		}

		@Test
		@DisplayName("목표_칸수가_2_이상인_축제는_백필에서_제외된다")
		void 목표_칸수가_2_이상인_축제는_백필에서_제외된다() {
			// 형태는 백필 조건을 만족하지만 target_count 가 2라 D-9 의 보존 고리가 성립하지 않는다.
			long missionId = 미션("EVENT", 2);
			격자블록(missionId, 9, 9);

			백필실행();

			assertThat(대표격자(missionId)).isNull();
		}
	}

	@Nested
	@DisplayName("저장 계층 제약")
	class 제약 {

		@Test
		@DisplayName("대표_격자가_있는_행의_목표_칸수를_2로_바꾸면_CHECK_제약이_거부한다")
		void 대표_격자가_있는_행의_목표_칸수를_2로_바꾸면_CHECK_제약이_거부한다() {
			long missionId = 미션("EVENT", 1);
			격자블록(missionId, 1, 1);
			대표격자지정(missionId, BASE_Y + "_" + BASE_X);

			assertThatThrownBy(() -> {
				em.createNativeQuery("UPDATE missions SET target_count = 2 WHERE id = :m")
					.setParameter("m", missionId).executeUpdate();
				em.flush();
			}).hasMessageContaining("chk_missions_rep_grid_type");
		}

		@Test
		@DisplayName("코스_미션에_대표_격자를_넣으면_CHECK_제약이_거부한다")
		void 코스_미션에_대표_격자를_넣으면_CHECK_제약이_거부한다() {
			long missionId = 미션("COURSE", 1);
			격자블록(missionId, 1, 1);

			assertThatThrownBy(() -> 대표격자지정(missionId, BASE_Y + "_" + BASE_X))
				.hasMessageContaining("chk_missions_rep_grid_type");
		}

		@Test
		@DisplayName("판정_격자_집합_밖의_대표_격자는_커밋_시점에_거부된다")
		void 판정_격자_집합_밖의_대표_격자는_커밋_시점에_거부된다() {
			long missionId = 미션("EVENT", 1);
			격자블록(missionId, 1, 1);

			// 지연 제약이라 UPDATE 문 자체는 통과한다 — 그 사실이 이 테스트의 절반이다.
			대표격자지정(missionId, (BASE_Y + 50) + "_" + BASE_X);

			assertThatThrownBy(MissionRepresentativeGridMigrationTest.this::제약즉시검사)
				.hasMessageContaining("fk_missions_rep_grid");
		}
	}
}
