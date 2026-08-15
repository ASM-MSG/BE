package com.msg.fillmap.grid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.geodesic.Geodesic;

import com.msg.fillmap.grid.dto.ViewportBounds;

class GridEncoderTest {

	// 셀 변 길이 허용 오차(m). 5179 는 축척계수 0.9996 이라 지상 실거리가 도평면 100m 와 최대 수 cm 차이 난다
	// (실측: 서울 100.038m·부산 100.015m·제주 100.030m).
	private static final Offset<Double> SIDE_TOLERANCE_METERS = Offset.offset(0.1);

	// 5179 기준 강남역이 속한 셀 — 기대값은 Proj4J 밖의 독립 구현(pyproj/PROJ 9.3.0)으로 교차 산출했다.
	private static final String GANGNAM_GRID_ID = "19443_9582";

	// 테스트 점을 5179 미터 좌표에 놓기 위한 계약 문자열 변환 (grid-epsg5179-samples.json 생성과 같은 방식).
	private static final CRSFactory CRS_FACTORY = new CRSFactory();
	private static final CoordinateReferenceSystem WGS84 =
		CRS_FACTORY.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs");
	private static final CoordinateReferenceSystem EPSG5179 =
		CRS_FACTORY.createFromParameters("EPSG:5179", GridConstants.CRS_DEF_EPSG5179);
	private static final CoordinateTransform TO_DEGREES =
		new CoordinateTransformFactory().createTransform(EPSG5179, WGS84);
	private static final CoordinateTransform TO_METERS =
		new CoordinateTransformFactory().createTransform(WGS84, EPSG5179);

	// ==================== 정방향 (좌표 → 격자) ====================

	// 검증: FR-GRID-01
	@Test
	void 강남역_좌표는_같은_격자로_매핑된다() {
		assertThat(GridEncoder.encode(37.4979, 127.0276)).isEqualTo(GANGNAM_GRID_ID);

		assertThat(GridEncoder.encode(37.4972, 127.0273)).isEqualTo(GANGNAM_GRID_ID);
		assertThat(GridEncoder.encode(37.4978, 127.0280)).isEqualTo(GANGNAM_GRID_ID);
	}

	// 검증: FR-GRID-01
	@Test
	void 서로_다른_셀의_좌표는_다른_grid_id를_반환한다() {
		assertThat(GridEncoder.encode(37.4989, 127.0276)).isNotEqualTo(GANGNAM_GRID_ID);   // 북쪽 한 칸 이상
		assertThat(GridEncoder.encode(37.4979, 127.0290)).isNotEqualTo(GANGNAM_GRID_ID);   // 동쪽 한 칸 이상
	}

	// 검증: FR-GRID-01
	@Test
	void grid_id_포맷은_grid_y_언더바_grid_x_형식이다() {
		GridEncoder.GridIndex index = GridEncoder.decode(GANGNAM_GRID_ID);

		assertThat(index.gridY()).isEqualTo(19443L);
		assertThat(index.gridX()).isEqualTo(9582L);
	}

	// 검증: FR-GRID-01
	@Test
	void 서울과_부산과_제주에서_셀_변_길이가_모두_100m다() {
		List<String> gridIds = List.of(
			GridEncoder.encode(37.4979, 127.0276),   // 서울 강남역
			GridEncoder.encode(35.1578, 129.0594),   // 부산 서면
			GridEncoder.encode(33.4996, 126.5312));  // 제주시

		for (String gridId : gridIds) {
			List<GridEncoder.GridPoint> ring = GridEncoder.bbox(gridId);
			for (int i = 0; i < 4; i++) {
				assertThat(sideLengthMeters(ring.get(i), ring.get(i + 1)))
					.as("%s 의 %d번째 변", gridId, i)
					.isCloseTo(100.0, SIDE_TOLERANCE_METERS);
			}
		}
	}

	// 검증: FR-GRID-02
	@Test
	void 셀_경계선_위의_좌표는_한_셀에만_속한다() {
		// 셀 남서 꼭짓점에서 대각으로 약 1m 씩 움직이면 경계 안쪽은 그 셀, 바깥쪽은 남서 이웃 셀이다.
		GridEncoder.GridPoint southWest = GridEncoder.bbox(GANGNAM_GRID_ID).get(0);
		double nudge = 0.00001;

		assertThat(GridEncoder.encode(southWest.lat() + nudge, southWest.lon() + nudge))
			.isEqualTo(GANGNAM_GRID_ID);
		assertThat(GridEncoder.encode(southWest.lat() - nudge, southWest.lon() - nudge))
			.isEqualTo("19442_9581");
	}

	// 검증: FR-GRID-01
	@Test
	void encode_decode_왕복이_항등이다() {
		String gridId = GridEncoder.encode(37.4979, 127.0276);
		GridEncoder.GridIndex index = GridEncoder.decode(gridId);

		assertThat(index.gridY() + "_" + index.gridX()).isEqualTo(gridId);
		assertThat(GridEncoder.encode(GridEncoder.center(gridId).lat(), GridEncoder.center(gridId).lon()))
			.isEqualTo(gridId);
	}

	// ==================== 역방향 (격자 → 중심좌표 / bbox) ====================

	@Test
	void center는_셀_꼭짓점_4점의_안쪽에_있다() {
		GridEncoder.GridPoint center = GridEncoder.center(GANGNAM_GRID_ID);
		List<GridEncoder.GridPoint> ring = GridEncoder.bbox(GANGNAM_GRID_ID);

		assertThat(center.lat())
			.isGreaterThan(maxLat(ring.subList(0, 2)))    // 남쪽 두 꼭짓점보다 북쪽
			.isLessThan(minLat(ring.subList(2, 4)));      // 북쪽 두 꼭짓점보다 남쪽
		assertThat(center.lon())
			.isGreaterThan(Math.max(ring.get(0).lon(), ring.get(3).lon()))   // 서쪽 두 꼭짓점보다 동쪽
			.isLessThan(Math.min(ring.get(1).lon(), ring.get(2).lon()));     // 동쪽 두 꼭짓점보다 서쪽
		assertThat(GridEncoder.encode(center.lat(), center.lon())).isEqualTo(GANGNAM_GRID_ID);
	}

	// 검증: FR-GRID-03
	@Test
	void bbox는_닫힌_링_5점을_유지한다() {
		List<GridEncoder.GridPoint> ring = GridEncoder.bbox(GANGNAM_GRID_ID);

		assertThat(ring).hasSize(5);
		assertThat(ring.get(4)).isEqualTo(ring.get(0));
		// 자오선 수렴으로 셀이 위경도 평면에서 기울어져 꼭짓점 4점이 서로 다른 값이다 (남서/북동 2점 복원 불가).
		assertThat(ring.get(0).lat()).isNotEqualTo(ring.get(1).lat());
		assertThat(ring.get(1).lon()).isNotEqualTo(ring.get(2).lon());
	}

	// ==================== 뷰포트 범위 ====================

	// 검증: FR-GRID-03
	@Test
	void 뷰포트_꼭짓점_4점의_minmax_범위가_뷰포트_안_모든_셀을_포함한다() {
		// 중앙자오선(경도 127.5)에서 먼 부산 일대 — 격자 축 기울기가 커서 2점 방식의 누락이 드러난다.
		ViewportBounds bounds = new ViewportBounds(35.05, 128.90, 35.25, 129.20);
		GridEncoder.GridRange range = GridEncoder.viewportRange(bounds);
		GridEncoder.GridIndex sw = GridEncoder.decode(GridEncoder.encode(bounds.swLat(), bounds.swLng()));
		GridEncoder.GridIndex ne = GridEncoder.decode(GridEncoder.encode(bounds.neLat(), bounds.neLng()));

		int missedByTwoCorners = 0;
		for (int i = 0; i <= 40; i++) {
			for (int j = 0; j <= 40; j++) {
				double lat = bounds.swLat() + (bounds.neLat() - bounds.swLat()) * i / 40;
				double lon = bounds.swLng() + (bounds.neLng() - bounds.swLng()) * j / 40;
				GridEncoder.GridIndex index = GridEncoder.decode(GridEncoder.encode(lat, lon));

				assertThat(index.gridY()).as("(%s, %s) 의 gridY", lat, lon)
					.isBetween(range.minGridY(), range.maxGridY());
				assertThat(index.gridX()).as("(%s, %s) 의 gridX", lat, lon)
					.isBetween(range.minGridX(), range.maxGridX());
				if (index.gridY() < sw.gridY() || index.gridY() > ne.gridY()
					|| index.gridX() < sw.gridX() || index.gridX() > ne.gridX()) {
					missedByTwoCorners++;
				}
			}
		}
		// 남서·북동 2점만 쓰던 구 방식이면 빠졌을 셀이 실제로 있다 — 헬퍼가 필요한 이유의 회귀 방지.
		assertThat(missedByTwoCorners).isPositive();
	}

	// ==================== 계약·동시성 ====================

	// 검증: FR-GRID-05
	@Test
	void 계약_문자열은_내장_EPSG_5179_정의와_같은_격자를_만든다() {
		// proj4j-epsg 레지스트리의 EPSG:5179 정의로 같은 좌표를 변환해 계약 문자열이 표준과 어긋나지 않음을 본다.
		CRSFactory crsFactory = new CRSFactory();
		CoordinateReferenceSystem wgs84 =
			crsFactory.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs");
		CoordinateReferenceSystem registry5179 = crsFactory.createFromName("EPSG:5179");
		var transform = new CoordinateTransformFactory().createTransform(wgs84, registry5179);

		for (double[] point : new double[][] {{37.4979, 127.0276}, {35.1578, 129.0594}, {33.4996, 126.5312}}) {
			ProjCoordinate meters = transform.transform(new ProjCoordinate(point[1], point[0]), new ProjCoordinate());
			String expected = (long) Math.floor(meters.y / GridConstants.CELL_SIZE_METERS)
				+ "_" + (long) Math.floor(meters.x / GridConstants.CELL_SIZE_METERS);
			assertThat(GridEncoder.encode(point[0], point[1])).isEqualTo(expected);
		}
	}

	@Test
	void 동시_호출에도_같은_격자를_반환한다() {
		// 변환 객체를 스레드 간 공유하므로(ProjCoordinate 만 호출마다 신규) 병렬 호출 결과가 단일 스레드와 같아야 한다.
		String expected = GridEncoder.encode(37.4979, 127.0276);

		assertThat(IntStream.range(0, 2000).parallel()
			.mapToObj(i -> GridEncoder.encode(37.4979, 127.0276))
			.distinct()
			.toList()).containsExactly(expected);
	}

	// ==================== 반경 판정 범위 (MSG-385) ====================
	// 강남역 셀 19443_9582 의 5179 미터 범위: x ∈ [958200, 958300), y ∈ [1944300, 1944400).

	@Test
	void 셀_가운데_점의_사방_40m_범위는_자기_셀_하나다() {
		GridEncoder.GridPoint point = pointAtMeters(958250, 1944350);

		assertThat(GridEncoder.radiusRange(point.lat(), point.lon(), 40))
			.isEqualTo(new GridEncoder.GridRange(19443, 19443, 9582, 9582));
	}

	@Test
	void 경계에서_40m_안쪽이면_이웃_셀이_범위에_들어온다() {
		// 동쪽 경계에서 20m — 사방 40m 가 경계를 20m 넘어 동쪽 이웃 셀에 걸친다 (1×2).
		GridEncoder.GridPoint point = pointAtMeters(958280, 1944350);

		assertThat(GridEncoder.radiusRange(point.lat(), point.lon(), 40))
			.isEqualTo(new GridEncoder.GridRange(19443, 19443, 9582, 9583));
	}

	@Test
	void 모서리_근처_점은_2x2_범위가_된다() {
		// 북동 모서리에서 축마다 20m — 양축 모두 경계를 넘어 2×2.
		GridEncoder.GridPoint point = pointAtMeters(958280, 1944380);

		assertThat(GridEncoder.radiusRange(point.lat(), point.lon(), 40))
			.isEqualTo(new GridEncoder.GridRange(19443, 19444, 9582, 9583));
	}

	@Test
	void 사방_40m가_경계에_정확히_닿기만_하면_그_셀은_제외된다() {
		// 반열림 정합 (ceil - 1 가드): 정사각형이 동쪽 경계에 닿기만 하면 이웃 셀은 제외된다.
		// 역변환 왕복 오차(µm대)가 닿음 판정을 뒤집지 않도록 반경은 정변환 실측 거리로 잡는다 —
		// Sterbenz 정리로 boundary - x 는 double 에서 정확해 x + radius 가 경계값과 정확히 일치한다.
		double eastBoundary = 958300;
		GridEncoder.GridPoint point = pointAtMeters(eastBoundary - 40, 1944350);
		double exactRadius = eastBoundary - toMetersX(point);

		assertThat(exactRadius).isCloseTo(40.0, Offset.offset(0.01));
		assertThat(GridEncoder.radiusRange(point.lat(), point.lon(), exactRadius))
			.isEqualTo(new GridEncoder.GridRange(19443, 19443, 9582, 9582));
	}

	@Test
	void 산출_범위는_항상_자기_셀을_포함한다() {
		// encode 정합 — 자기 셀은 거리 0 이라 경계선 위의 점에서도 범위에 든다.
		GridEncoder.GridPoint southWest = GridEncoder.bbox(GANGNAM_GRID_ID).get(0);
		double[][] points = {{37.4979, 127.0276}, {35.1578, 129.0594}, {33.4996, 126.5312},
			{southWest.lat(), southWest.lon()}};

		for (double[] point : points) {
			GridEncoder.GridIndex index = GridEncoder.decode(GridEncoder.encode(point[0], point[1]));
			GridEncoder.GridRange range = GridEncoder.radiusRange(point[0], point[1], 40);

			assertThat(index.gridY()).as("(%s, %s) 의 gridY", point[0], point[1])
				.isBetween(range.minGridY(), range.maxGridY());
			assertThat(index.gridX()).as("(%s, %s) 의 gridX", point[0], point[1])
				.isBetween(range.minGridX(), range.maxGridX());
		}
	}

	private static GridEncoder.GridPoint pointAtMeters(double x, double y) {
		ProjCoordinate degrees = TO_DEGREES.transform(new ProjCoordinate(x, y), new ProjCoordinate());
		return new GridEncoder.GridPoint(degrees.y, degrees.x);
	}

	private static double toMetersX(GridEncoder.GridPoint point) {
		return TO_METERS.transform(new ProjCoordinate(point.lon(), point.lat()), new ProjCoordinate()).x;
	}

	private static double sideLengthMeters(GridEncoder.GridPoint from, GridEncoder.GridPoint to) {
		return Geodesic.WGS84.Inverse(from.lat(), from.lon(), to.lat(), to.lon()).s12;
	}

	private static double maxLat(List<GridEncoder.GridPoint> points) {
		return points.stream().mapToDouble(GridEncoder.GridPoint::lat).max().orElseThrow();
	}

	private static double minLat(List<GridEncoder.GridPoint> points) {
		return points.stream().mapToDouble(GridEncoder.GridPoint::lat).min().orElseThrow();
	}
}
