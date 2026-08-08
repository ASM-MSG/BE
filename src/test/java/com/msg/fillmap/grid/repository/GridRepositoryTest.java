package com.msg.fillmap.grid.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

@SpringBootTest
@Transactional
@DisplayName("GridRepository 조회 (실 PostGIS) — 접근 A·B")
class GridRepositoryTest {

	// 성수 인근 임의 기준점. 실제 grid_y/grid_x 는 BeforeEach 에서 GridEncoder 로 산출한다.
	private static final double 성수_LAT = 37.5445;
	private static final double 성수_LON = 127.0560;

	@Autowired
	private GridRepository gridRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long me;
	private long other;

	private long baseY;
	private long baseX;

	private String g00;
	private String g11;
	private String g22;
	private String g02;
	private String g20;
	private String gOut;

	private double swLat;
	private double swLng;
	private double neLat;
	private double neLng;

	@BeforeEach
	void setUp() {
		User meUser = userRepository.save(User.createLocalUser("grid-me@example.com", "hash", "나"));
		User otherUser = userRepository.save(User.createLocalUser("grid-other@example.com", "hash", "남"));
		me = meUser.getId();
		other = otherUser.getId();

		GridIndex base = GridEncoder.decode(GridEncoder.encode(성수_LAT, 성수_LON));
		baseY = base.gridY();
		baseX = base.gridX();

		// 3×3 블록 내 셀들 + 블록 밖 셀 하나를 시드한다.
		g00 = GridFixtures.seedGrid(em, baseY, baseX);
		g11 = GridFixtures.seedGrid(em, baseY + 1, baseX + 1);
		g22 = GridFixtures.seedGrid(em, baseY + 2, baseX + 2);
		g02 = GridFixtures.seedGrid(em, baseY, baseX + 2);
		g20 = GridFixtures.seedGrid(em, baseY + 2, baseX);
		gOut = GridFixtures.seedGrid(em, baseY + 10, baseX);

		// 내 점령: g00·g11·g22 (블록 안). 타인 점령: g02. 미점령: g20(grid 만 존재). 블록 밖 내 점령: gOut.
		GridFixtures.seedUserGrid(em, me, g00, 3);
		GridFixtures.seedUserGrid(em, me, g11, 1);
		GridFixtures.seedUserGrid(em, me, g22, 2);
		GridFixtures.seedUserGrid(em, other, g02, 5);
		GridFixtures.seedUserGrid(em, me, gOut, 4);

		// 뷰포트 코너를 셀 경계가 아닌 셀 중심에 두어 A/B 경계 판정이 부동소수 오차 없이 일치하게 한다.
		GridPoint southWest = GridFixtures.pointAt(baseY + 0.5, baseX + 0.5);
		GridPoint northEast = GridFixtures.pointAt(baseY + 2.5, baseX + 2.5);
		swLat = southWest.lat();
		swLng = southWest.lon();
		neLat = northEast.lat();
		neLng = northEast.lon();

		em.flush();
	}

	private List<String> rangeScanA() {
		GridIndex sw = GridEncoder.decode(GridEncoder.encode(swLat, swLng));
		GridIndex ne = GridEncoder.decode(GridEncoder.encode(neLat, neLng));
		return gridRepository.findOccupiedInRange(me, sw.gridY(), ne.gridY(), sw.gridX(), ne.gridX())
			.stream().map(OccupiedGridProjection::getGridId).toList();
	}

	private List<String> gistB() {
		return gridRepository.findOccupiedByIntersects(me, swLng, swLat, neLng, neLat)
			.stream().map(OccupiedGridProjection::getGridId).toList();
	}

	private List<OccupiedGridProjection> pageA(int limit) {
		GridIndex sw = GridEncoder.decode(GridEncoder.encode(swLat, swLng));
		GridIndex ne = GridEncoder.decode(GridEncoder.encode(neLat, neLng));
		return gridRepository.findOccupiedPage(me, sw.gridY(), ne.gridY(), sw.gridX(), ne.gridX(), limit);
	}

	private List<OccupiedGridProjection> pageAAfter(long cursorY, long cursorX, int limit) {
		GridIndex sw = GridEncoder.decode(GridEncoder.encode(swLat, swLng));
		GridIndex ne = GridEncoder.decode(GridEncoder.encode(neLat, neLng));
		return gridRepository.findOccupiedPageAfter(
			me, sw.gridY(), ne.gridY(), sw.gridX(), ne.gridX(), cursorY, cursorX, limit);
	}

	private List<String> gridIds(List<OccupiedGridProjection> rows) {
		return rows.stream().map(OccupiedGridProjection::getGridId).toList();
	}

	@Test
	@DisplayName("점령한 격자를 단일 조회하면 videoCount 를 반환한다")
	void 점령한_격자를_단일_조회하면_videoCount를_반환한다() {
		assertThat(gridRepository.findVideoCount(me, g00)).contains(3);
	}

	@Test
	@DisplayName("점령하지 않은 격자를 단일 조회하면 결과가 비어있다")
	void 점령하지_않은_격자를_단일_조회하면_결과가_비어있다() {
		assertThat(gridRepository.findVideoCount(me, g20)).isEmpty();
	}

	@Test
	@DisplayName("정수범위스캔 A 는 뷰포트 안의 내 점령 격자만 반환한다")
	void 정수범위스캔_A는_뷰포트_안의_내_점령_격자만_반환한다() {
		assertThat(rangeScanA()).containsExactlyInAnyOrder(g00, g11, g22);
	}

	@Test
	@DisplayName("GIST 공간쿼리 B 는 뷰포트 안의 내 점령 격자만 반환한다")
	void GIST공간쿼리_B는_뷰포트_안의_내_점령_격자만_반환한다() {
		assertThat(gistB()).containsExactlyInAnyOrder(g00, g11, g22);
	}

	@Test
	@DisplayName("정수범위스캔 A 와 GIST B 는 동일한 격자 집합을 반환한다 (경계 셀 정합)")
	void 정수범위스캔_A와_GIST_B는_동일한_격자_집합을_반환한다() {
		assertThat(rangeScanA()).containsExactlyInAnyOrderElementsOf(gistB());
	}

	@Test
	@DisplayName("뷰포트 범위 밖의 점령 격자는 반환하지 않는다")
	void 뷰포트_범위_밖의_점령_격자는_반환하지_않는다() {
		assertThat(rangeScanA()).doesNotContain(gOut);
		assertThat(gistB()).doesNotContain(gOut);
	}

	@Test
	@DisplayName("다른 사용자의 점령 격자는 내 뷰포트 결과에 포함되지 않는다 (개인 도감 격리)")
	void 다른_사용자의_점령_격자는_내_뷰포트_결과에_포함되지_않는다() {
		assertThat(rangeScanA()).doesNotContain(g02);
		assertThat(gistB()).doesNotContain(g02);
	}

	@Test
	@DisplayName("경계 셀이 뷰포트 범위에 포함된다")
	void 경계_셀이_뷰포트_범위에_포함된다() {
		// g22 는 블록 코너(뷰포트 북동 경계) 셀 — A·B 모두 포함해야 한다.
		assertThat(rangeScanA()).contains(g22);
		assertThat(gistB()).contains(g22);
	}

	@Test
	@DisplayName("GIST 쿼리는 경도위도 순서로 envelope 를 만든다 (축 순서 회귀)")
	void GIST쿼리는_경도위도_순서로_envelope를_만든다() {
		// 인자를 (lng, lat) 순서로 넘겨야 한국 좌표(lon≈127, lat≈37)에 envelope 가 생겨 결과가 나온다.
		// swLng/swLat 가 뒤집히면 envelope 가 서비스 지역 밖으로 이동해 빈 결과가 된다.
		assertThat(gistB()).isNotEmpty().containsExactlyInAnyOrder(g00, g11, g22);
	}

	@Test
	@DisplayName("커서 없이 조회하면 정렬 첫 페이지를 size 만큼 반환한다 (MSG-90)")
	void 커서없이_조회하면_정렬첫페이지를_size만큼_반환한다() {
		// 블록 안 내 점령의 (grid_y, grid_x) 정렬: g00 → g11 → g22. 첫 페이지(limit 2)는 앞 2개다.
		assertThat(gridIds(pageA(2))).containsExactly(g00, g11);
	}

	@Test
	@DisplayName("커서 이후 조회하면 그 커서보다 큰 격자만 grid_y, grid_x 순으로 반환한다 (MSG-90)")
	void 커서이후_조회하면_그_커서보다_큰_격자만_grid_y_grid_x_순으로_반환한다() {
		// 같은 grid_y 에서 grid_x 만 큰 셀도 커서 뒤로 이어져야 한다(행 값 비교 검증).
		String gMid = GridFixtures.seedGrid(em, baseY + 1, baseX + 2);
		GridFixtures.seedUserGrid(em, me, gMid, 1);

		List<String> result = gridIds(pageAAfter(baseY + 1, baseX + 1, 10));

		assertThat(result).containsExactly(gMid, g22);
		assertThat(result).doesNotContain(g00, g11);
	}

	@Test
	@DisplayName("페이지를 끝까지 이어붙이면 비페이지 전체 결과와 동일 집합이다 (누락·중복 없음)")
	void 페이지를_끝까지_이어붙이면_비페이지_전체결과와_동일_집합이다() {
		List<String> collected = new ArrayList<>();
		List<OccupiedGridProjection> page = pageA(2);
		while (!page.isEmpty()) {
			collected.addAll(gridIds(page));
			OccupiedGridProjection last = page.get(page.size() - 1);
			page = pageAAfter(last.getGridY(), last.getGridX(), 2);
		}

		assertThat(collected).doesNotHaveDuplicates();
		assertThat(collected).containsExactlyInAnyOrderElementsOf(rangeScanA());
	}

	@Test
	@DisplayName("결과는 항상 grid_y, grid_x 오름차순 정렬이다 (MSG-90)")
	void 결과는_항상_grid_y_grid_x_오름차순_정렬이다() {
		// 같은 grid_y 안의 x 타이브레이크까지 검증하도록 (y0, x1)·(y2, x0) 점령을 추가한다.
		String gLowX = GridFixtures.seedGrid(em, baseY, baseX + 1);
		GridFixtures.seedUserGrid(em, me, gLowX, 1);
		GridFixtures.seedUserGrid(em, me, g20, 1);

		// 기대 정렬: (y0,x0) → (y0,x1) → (y1,x1) → (y2,x0) → (y2,x2)
		assertThat(gridIds(pageA(10))).containsExactly(g00, gLowX, g11, g20, g22);
	}

	@Test
	@DisplayName("뷰포트 밖 격자와 타인 점령 격자는 페이지에 포함되지 않는다 (개인 도감 격리 유지)")
	void 뷰포트_밖_격자와_타인_점령_격자는_페이지에_포함되지_않는다() {
		assertThat(gridIds(pageA(10)))
			.containsExactly(g00, g11, g22)
			.doesNotContain(gOut, g02);
	}
}
