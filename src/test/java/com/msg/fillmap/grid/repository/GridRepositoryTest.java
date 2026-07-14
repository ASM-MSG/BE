package com.msg.fillmap.grid.repository;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;
import static org.assertj.core.api.Assertions.assertThat;

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
		long baseY = base.gridY();
		long baseX = base.gridX();

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
		swLat = (baseY + 0.5) * GRID_LAT_STEP;
		swLng = (baseX + 0.5) * GRID_LNG_STEP;
		neLat = (baseY + 2.5) * GRID_LAT_STEP;
		neLng = (baseX + 2.5) * GRID_LNG_STEP;

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
}
