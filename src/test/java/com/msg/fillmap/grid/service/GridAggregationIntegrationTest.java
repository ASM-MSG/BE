package com.msg.fillmap.grid.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.region.RegionTestFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 뷰포트 격자 집계 조회 통합 (MSG-356). 실 PostGIS 로 GROUP BY·AVG·LEFT JOIN 동작까지 확인한다.
 * 격리(공유 로컬 DB): 실 행정동이 덮지 않는 서해 공해상 블록 + 실존하지 않는 9997 대역 합성 행정동만 쓴다.
 * 정리는 @Transactional 롤백이고, 옛 실행이 남긴 잔존 행은 setUp 의 선행 정리가 걷어낸다.
 */
@SpringBootTest
@Transactional
@DisplayName("격자 집계 조회 통합 (실 PostGIS)")
class GridAggregationIntegrationTest {

	/** 서해 공해상 기준점 — 실 행정동 경계 밖이라 이 테스트가 심는 합성 행정동만 판정에 잡힌다. */
	private static final double 공해상_LAT = 36.4;
	private static final double 공해상_LON = 124.4;

	/** 합성 행정동 3개. 앞 5자리(시군구)는 A·B 가 같고 C 만 다르며, 앞 2자리(시도)는 셋 다 같다. */
	private static final String DONG_A = "9997110001";
	private static final String DONG_B = "9997110002";
	private static final String DONG_C = "9997220003";
	private static final String NAME_A = "합성광역시 합성일구 합성일동";
	private static final String NAME_B = "합성광역시 합성일구 합성이동";
	private static final String NAME_C = "합성광역시 합성이구 합성삼동";

	@Autowired
	private GridQueryService gridQueryService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	private long me;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		me = userRepository.save(User.createLocalUser("grid-agg@example.com", "hash", "나")).getId();

		GridIndex base = GridEncoder.decode(GridEncoder.encode(공해상_LAT, 공해상_LON));
		baseY = base.gridY();
		baseX = base.gridX();
		cleanBlock();

		// 행정동 블록은 10행(1km)씩 띄워 심는다 — 격자 축 기울기로 경계 사각형이 조금 넓어져도 겹치지 않는다.
		seedRegion(DONG_A, NAME_A, 0);
		seedRegion(DONG_B, NAME_B, 10);
		seedRegion(DONG_C, NAME_C, 20);

		// A 2칸, B 1칸, C 1칸, 무귀속 1칸 = 총 5칸 점령.
		seedOccupied(0, 0);
		seedOccupied(0, 1);
		seedOccupied(10, 0);
		seedOccupied(20, 0);
		seedUnlabeledOccupied(30, 0);
		em.flush();
	}

	/**
	 * 공유 로컬 DB 잔존 행 정리 — 옛 실행(부하·시드)이 이 블록에 남긴 격자가 있으면 region_code 라벨이
	 * 달라 기대값이 흔들린다. 다른 데이터가 참조 중인 격자는 남의 것이므로 건드리지 않는다.
	 */
	private void cleanBlock() {
		em.createNativeQuery("""
			DELETE FROM user_grids ug USING grids g
			WHERE ug.grid_id = g.grid_id
				AND g.grid_y BETWEEN :minY AND :maxY AND g.grid_x BETWEEN :minX AND :maxX
			""")
			.setParameter("minY", baseY).setParameter("maxY", baseY + 40)
			.setParameter("minX", baseX).setParameter("maxX", baseX + 10)
			.executeUpdate();
		em.createNativeQuery("""
			DELETE FROM grids g
			WHERE g.grid_y BETWEEN :minY AND :maxY AND g.grid_x BETWEEN :minX AND :maxX
				AND NOT EXISTS (SELECT 1 FROM videos v WHERE v.grid_id = g.grid_id)
				AND NOT EXISTS (SELECT 1 FROM sponsor_ads sa WHERE sa.grid_id = g.grid_id)
			""")
			.setParameter("minY", baseY).setParameter("maxY", baseY + 40)
			.setParameter("minX", baseX).setParameter("maxX", baseX + 10)
			.executeUpdate();
	}

	/** (baseY + dy) 부터 3행 × 3열 블록을 덮는 합성 행정동. */
	private void seedRegion(String regionCode, String regionName, long dy) {
		regionRepository.upsert(regionCode, regionName, regionCode.substring(0, 5),
			RegionTestFixtures.cellBlockPolygonJson(baseY + dy, baseY + dy + 3, baseX, baseX + 3),
			RegionTestFixtures.CELL_AREA_M2);
	}

	/** 프로덕션과 같은 규칙(중심점 판정)으로 라벨된 격자를 내 점령으로 심는다. */
	private String seedOccupied(long dy, long dx) {
		String gridId = GridFixtures.seedLabeledGrid(em, baseY + dy, baseX + dx);
		GridFixtures.seedUserGrid(em, me, gridId, 1);
		return gridId;
	}

	/** 어느 행정동도 덮지 않는 위치의 격자 — region_code 가 NULL 로 남는다 (FR-7 재료). */
	private String seedUnlabeledOccupied(long dy, long dx) {
		String gridId = GridFixtures.seedGrid(em, baseY + dy, baseX + dx);
		GridFixtures.seedUserGrid(em, me, gridId, 1);
		return gridId;
	}

	/** 시드 블록 전체를 감싸는 뷰포트. 격자 축이 위경도 축과 안 맞아 꼭짓점 4점의 min/max 로 잡는다. */
	private ViewportBounds blockBounds() {
		GridPoint southWest = GridFixtures.pointAt(baseY - 1, baseX - 1);
		GridPoint southEast = GridFixtures.pointAt(baseY - 1, baseX + 4);
		GridPoint northEast = GridFixtures.pointAt(baseY + 32, baseX + 4);
		GridPoint northWest = GridFixtures.pointAt(baseY + 32, baseX - 1);
		return new ViewportBounds(
			Math.min(southWest.lat(), southEast.lat()), Math.min(southWest.lon(), northWest.lon()),
			Math.max(northEast.lat(), northWest.lat()), Math.max(northEast.lon(), southEast.lon()));
	}

	private static RegionAggregateView itemOf(List<RegionAggregateView> items, String regionCode) {
		return items.stream()
			.filter(item -> regionCode.equals(item.regionCode()))
			.findFirst()
			.orElseThrow();
	}

	@Test
	@DisplayName("동 단위 집계는 행정동별로 묶어 격자 수를 센다")
	void 동_단위_집계는_행정동별로_묶어_격자_수를_센다() {
		List<RegionAggregateView> items =
			gridQueryService.getOccupiedAggregatesInViewport(me, blockBounds(), RegionUnit.DONG);

		assertThat(items).extracting(RegionAggregateView::regionCode)
			.containsExactly(DONG_A, DONG_B, DONG_C, null);
		assertThat(itemOf(items, DONG_A).name()).isEqualTo("합성일동");
		assertThat(itemOf(items, DONG_A).count()).isEqualTo(2);
		assertThat(itemOf(items, DONG_B).count()).isEqualTo(1);
		assertThat(itemOf(items, DONG_C).count()).isEqualTo(1);
	}

	@Test
	@DisplayName("구 단위 집계는 행정동 코드 앞 5자리로 묶는다")
	void 구_단위_집계는_행정동_코드_앞_5자리로_묶는다() {
		List<RegionAggregateView> items =
			gridQueryService.getOccupiedAggregatesInViewport(me, blockBounds(), RegionUnit.SIGUNGU);

		// A·B 는 같은 시군구(99971)라 한 항목으로 합쳐지고 C(99972)는 따로 남는다.
		assertThat(items).extracting(RegionAggregateView::regionCode)
			.containsExactly("99971", "99972", null);
		assertThat(itemOf(items, "99971").name()).isEqualTo("합성일구");
		assertThat(itemOf(items, "99971").count()).isEqualTo(3);
		assertThat(itemOf(items, "99972").name()).isEqualTo("합성이구");
		assertThat(itemOf(items, "99972").count()).isEqualTo(1);
	}

	@Test
	@DisplayName("시 단위 이름은 시도 토큰이다")
	void 시_단위_이름은_시도_토큰이다() {
		List<RegionAggregateView> items =
			gridQueryService.getOccupiedAggregatesInViewport(me, blockBounds(), RegionUnit.SIDO);

		assertThat(items).extracting(RegionAggregateView::regionCode).containsExactly("99", null);
		assertThat(itemOf(items, "99").name()).isEqualTo("합성광역시");
		assertThat(itemOf(items, "99").count()).isEqualTo(4);
	}

	@Test
	@DisplayName("집계 합은 같은 뷰포트 개별 조회 총수와 같다 (동·구·시 전 단위, FR-3)")
	void 집계_합은_같은_뷰포트_개별_조회_총수와_같다() {
		int individual = gridQueryService.getOccupiedInViewport(me, blockBounds()).size();

		for (RegionUnit unit : RegionUnit.values()) {
			int aggregated = gridQueryService.getOccupiedAggregatesInViewport(me, blockBounds(), unit)
				.stream()
				.mapToInt(RegionAggregateView::count)
				.sum();
			assertThat(aggregated).isEqualTo(individual).isEqualTo(5);
		}
	}

	@Test
	@DisplayName("행정동 미판정 격자는 이름 없는 항목 하나로 집계된다 (FR-7)")
	void 행정동_미판정_격자는_이름_없는_항목_하나로_집계된다() {
		seedUnlabeledOccupied(31, 0);
		em.flush();

		List<RegionAggregateView> items =
			gridQueryService.getOccupiedAggregatesInViewport(me, blockBounds(), RegionUnit.DONG);

		List<RegionAggregateView> unlabeled = items.stream()
			.filter(item -> item.regionCode() == null)
			.toList();
		assertThat(unlabeled).hasSize(1);
		assertThat(unlabeled.get(0).name()).isNull();
		assertThat(unlabeled.get(0).count()).isEqualTo(2);
		// 정렬 규칙상 미판정 항목은 항상 마지막이다.
		assertThat(items.get(items.size() - 1).regionCode()).isNull();
	}

	@Test
	@DisplayName("대표 좌표는 그룹 점령 격자 중심의 평균이다")
	void 대표_좌표는_그룹_점령_격자_중심의_평균이다() {
		GridPoint first = GridFixtures.pointAt(baseY + 0.5, baseX + 0.5);
		GridPoint second = GridFixtures.pointAt(baseY + 0.5, baseX + 1.5);

		RegionAggregateView item = itemOf(
			gridQueryService.getOccupiedAggregatesInViewport(me, blockBounds(), RegionUnit.DONG), DONG_A);

		assertThat(item.lat()).isCloseTo((first.lat() + second.lat()) / 2, within(1e-7));
		assertThat(item.lng()).isCloseTo((first.lon() + second.lon()) / 2, within(1e-7));
	}

	@Test
	@DisplayName("점령 격자가 없는 뷰포트는 빈 목록이다 (FR-5)")
	void 점령_격자가_없는_뷰포트는_빈_목록이다() {
		// 시드 블록에서 50km 떨어진 작은 뷰포트 — 내 점령 격자가 하나도 없다.
		GridPoint southWest = GridFixtures.pointAt(baseY + 500, baseX + 500);
		GridPoint northEast = GridFixtures.pointAt(baseY + 503, baseX + 503);
		ViewportBounds empty =
			new ViewportBounds(southWest.lat(), southWest.lon(), northEast.lat(), northEast.lon());

		assertThat(gridQueryService.getOccupiedAggregatesInViewport(me, empty, RegionUnit.DONG)).isEmpty();
	}

	@Test
	@DisplayName("동 단위는 한 변 1도 초과를 4402 로 거절한다")
	void 동_단위는_한_변_1도_초과를_4402로_거절한다() {
		ViewportBounds tooWide = new ViewportBounds(36.0, 124.0, 37.01, 124.5);

		assertThatThrownBy(() -> gridQueryService.getOccupiedAggregatesInViewport(me, tooWide, RegionUnit.DONG))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.VIEWPORT_TOO_LARGE);
	}

	@Test
	@DisplayName("구 단위는 한 변 4도 초과를 4402 로 거절한다")
	void 구_단위는_한_변_4도_초과를_4402로_거절한다() {
		ViewportBounds tooWide = new ViewportBounds(33.0, 124.0, 37.01, 124.5);

		// 같은 뷰포트가 동 단위 상한(1도)은 넘지만 구 단위는 4도라 4.01도에서 처음 걸린다.
		assertThatThrownBy(() -> gridQueryService.getOccupiedAggregatesInViewport(me, tooWide, RegionUnit.SIGUNGU))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.VIEWPORT_TOO_LARGE);
		assertThatCode(() -> gridQueryService.getOccupiedAggregatesInViewport(
			me, new ViewportBounds(33.0, 124.0, 37.0, 124.5), RegionUnit.SIGUNGU))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("시 단위는 전국 뷰포트를 허용한다")
	void 시_단위는_전국_뷰포트를_허용한다() {
		ViewportBounds korea = new ViewportBounds(33.0, 124.0, 38.7, 131.0);

		List<RegionAggregateView> items =
			gridQueryService.getOccupiedAggregatesInViewport(me, korea, RegionUnit.SIDO);

		assertThat(itemOf(items, "99").count()).isEqualTo(4);
	}

	@Test
	@DisplayName("시 단위는 한 변 10도 초과를 4402 로 거절한다")
	void 시_단위는_한_변_10도_초과를_4402로_거절한다() {
		// 전국 시야(약 5도)는 통과하되 그보다 두 배 넓은 요청은 유한 상한에서 걸린다.
		ViewportBounds tooWide = new ViewportBounds(28.0, 124.0, 38.01, 124.5);

		assertThatThrownBy(() -> gridQueryService.getOccupiedAggregatesInViewport(me, tooWide, RegionUnit.SIDO))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.VIEWPORT_TOO_LARGE);
	}

	@Test
	@DisplayName("비유한 좌표는 4401 로 거절한다 (NaN·무한대, 세 단위 공통)")
	void 비유한_좌표는_4401로_거절한다() {
		List<ViewportBounds> nonFinite = List.of(
			new ViewportBounds(Double.NaN, 124.0, 37.0, 124.5),
			new ViewportBounds(36.0, Double.NaN, 37.0, 124.5),
			new ViewportBounds(36.0, 124.0, Double.POSITIVE_INFINITY, 124.5),
			new ViewportBounds(36.0, 124.0, 37.0, Double.NEGATIVE_INFINITY));

		for (RegionUnit unit : RegionUnit.values()) {
			for (ViewportBounds bounds : nonFinite) {
				// NaN 은 어떤 비교도 false 라 상한 검사만으로는 그대로 통과한다 — 유한성 검사가 앞서야 막힌다.
				assertThatThrownBy(() -> gridQueryService.getOccupiedAggregatesInViewport(me, bounds, unit))
					.isInstanceOf(ApiException.class)
					.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
			}
		}
	}

	@Test
	@DisplayName("WGS84 범위 밖 좌표는 4401 로 거절한다 (위도 ±90·경도 ±180 이탈)")
	void WGS84_범위_밖_좌표는_4401로_거절한다() {
		List<ViewportBounds> outOfRange = List.of(
			new ViewportBounds(-90.1, 124.0, 37.0, 124.5),
			new ViewportBounds(36.0, 124.0, 100.0, 124.5),
			new ViewportBounds(36.0, -180.1, 37.0, 124.5),
			new ViewportBounds(36.0, 124.0, 37.0, 180.1));

		for (ViewportBounds bounds : outOfRange) {
			// 유한한 값이라 뒤집힘·상한 검사는 통과한다 — 조용한 빈 결과가 아니라 명시 거절이어야 한다.
			assertThatThrownBy(() -> gridQueryService.getOccupiedAggregatesInViewport(me, bounds, RegionUnit.DONG))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
		}
	}

	@Test
	@DisplayName("뒤집힌 bbox 는 4401 로 거절한다")
	void 뒤집힌_bbox는_4401로_거절한다() {
		ViewportBounds inverted = new ViewportBounds(37.60, 127.10, 37.50, 127.05);

		assertThatThrownBy(() -> gridQueryService.getOccupiedAggregatesInViewport(me, inverted, RegionUnit.SIDO))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
	}
}
