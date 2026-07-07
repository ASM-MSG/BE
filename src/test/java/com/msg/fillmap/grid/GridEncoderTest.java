package com.msg.fillmap.grid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class GridEncoderTest {

	private static final Offset<Double> EPS = Offset.offset(1.0e-9);

	// ==================== 정방향 (좌표 → 격자) ====================

	@Test
	void 강남역_좌표는_같은_격자로_매핑된다() {
		String gridId = GridEncoder.encode(37.4979, 127.0276);

		assertThat(GridEncoder.encode(37.4977, 127.0270)).isEqualTo(gridId);
		assertThat(GridEncoder.encode(37.4984, 127.0278)).isEqualTo(gridId);
	}

	@Test
	void 같은_셀_내_서로_다른_좌표는_동일한_grid_id를_반환한다() {
		// 스펙 예시 셀 "41642_110458" 내부의 서로 다른 두 좌표
		assertThat(GridEncoder.encode(37.4780, 127.0269))
			.isEqualTo(GridEncoder.encode(37.4786, 127.0277));
	}

	@Test
	void 서로_다른_셀의_좌표는_다른_grid_id를_반환한다() {
		String base = GridEncoder.encode(37.4780, 127.0269);

		assertThat(GridEncoder.encode(37.4790, 127.0269)).isNotEqualTo(base); // 위도 한 칸 위 셀
		assertThat(GridEncoder.encode(37.4780, 127.0280)).isNotEqualTo(base); // 경도 한 칸 옆 셀
	}

	@Test
	void 격자_경계에_걸친_좌표는_반열림구간_규칙으로_매핑된다() {
		double boundaryLat = 41642 * GridConstants.GRID_LAT_STEP; // 셀 41642 남쪽 경계 (포함)
		double boundaryLon = 110458 * GridConstants.GRID_LNG_STEP; // 셀 110458 서쪽 경계 (포함)

		assertThat(GridEncoder.encode(boundaryLat, boundaryLon)).isEqualTo("41642_110458");
		assertThat(GridEncoder.encode(boundaryLat - 0.00001, boundaryLon - 0.00001))
			.isEqualTo("41641_110457");
	}

	@Test
	void grid_id_포맷은_grid_y_언더바_grid_x_형식이다() {
		assertThat(GridEncoder.encode(37.4780, 127.0269)).isEqualTo("41642_110458");

		GridEncoder.GridIndex index = GridEncoder.decode("41642_110458");
		assertThat(index.gridY()).isEqualTo(41642L);
		assertThat(index.gridX()).isEqualTo(110458L);
	}

	@Test
	void 음수_경도_또는_적도_부근_좌표도_floor_규칙으로_매핑된다() {
		assertThat(GridEncoder.encode(0.0001, 0.0001)).isEqualTo("0_0");
		assertThat(GridEncoder.encode(-0.00045, -0.0006)).isEqualTo("-1_-1"); // floor(-0.5) = -1
		assertThat(GridEncoder.encode(40.7128, -74.0060)).isEqualTo("45236_-64354"); // 뉴욕 (음수 경도)

		GridEncoder.GridIndex index = GridEncoder.decode("-1_-1");
		assertThat(index.gridY()).isEqualTo(-1L);
		assertThat(index.gridX()).isEqualTo(-1L);
	}

	// ==================== 역방향 (격자 → 중심좌표 / bbox) ====================

	@Test
	void grid_id를_중심좌표로_역변환하면_해당_셀_안에_위치한다() {
		GridEncoder.GridPoint center = GridEncoder.center("41642_110458");

		assertThat(GridEncoder.encode(center.lat(), center.lon())).isEqualTo("41642_110458");
		assertThat(center.lat()).isCloseTo(41642.5 * GridConstants.GRID_LAT_STEP, EPS);
		assertThat(center.lon()).isCloseTo(110458.5 * GridConstants.GRID_LNG_STEP, EPS);
	}

	@Test
	void 좌표를_키로_변환한_뒤_중심좌표로_되돌리면_같은_셀로_매핑된다() {
		String gridId = GridEncoder.encode(37.4979, 127.0276);
		GridEncoder.GridPoint center = GridEncoder.center(gridId);

		assertThat(GridEncoder.encode(center.lat(), center.lon())).isEqualTo(gridId);
	}

	@Test
	void grid_id를_bbox_폴리곤으로_변환하면_100m_정사각형_닫힌링을_반환한다() {
		List<GridEncoder.GridPoint> ring = GridEncoder.bbox("41642_110458");

		assertThat(ring).hasSize(5);
		assertThat(ring.get(4)).isEqualTo(ring.get(0)); // 닫힌 링 (시작점 == 끝점)
		// 셀 한 변 = 스텝 크기 (한국 위도 기준 ≈100m 등간격 근사)
		assertThat(ring.get(2).lat() - ring.get(0).lat()).isCloseTo(GridConstants.GRID_LAT_STEP, EPS);
		assertThat(ring.get(2).lon() - ring.get(0).lon()).isCloseTo(GridConstants.GRID_LNG_STEP, EPS);
	}

	@Test
	void bbox의_남서점과_북동점은_grid_y_grid_x_스텝_경계와_일치한다() {
		List<GridEncoder.GridPoint> ring = GridEncoder.bbox("41642_110458");

		GridEncoder.GridPoint southWest = ring.get(0);
		GridEncoder.GridPoint northEast = ring.get(2);
		assertThat(southWest.lat()).isCloseTo(41642 * GridConstants.GRID_LAT_STEP, EPS);
		assertThat(southWest.lon()).isCloseTo(110458 * GridConstants.GRID_LNG_STEP, EPS);
		assertThat(northEast.lat()).isCloseTo(41643 * GridConstants.GRID_LAT_STEP, EPS);
		assertThat(northEast.lon()).isCloseTo(110459 * GridConstants.GRID_LNG_STEP, EPS);
	}
}
