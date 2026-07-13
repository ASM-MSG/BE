package com.msg.fillmap.grid.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.msg.fillmap.grid.AbstractPostgisTest;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridWkt;

class GridRepositoryTest extends AbstractPostgisTest {

	private static final double GANGNAM_LAT = 37.4979;
	private static final double GANGNAM_LON = 127.0276;

	@Autowired
	private GridRepository gridRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 새_격자를_등록하면_grids_row가_생성된다() {
		String gridId = GridEncoder.encode(GANGNAM_LAT, GANGNAM_LON);

		int affected = registerIfAbsent(gridId);

		assertThat(affected).isEqualTo(1);
		assertThat(gridRepository.findById(gridId)).isPresent();
	}

	@Test
	void 이미_등록된_격자에_다시_올리면_grids_row는_중복_생성되지_않는다() {
		String gridId = GridEncoder.encode(GANGNAM_LAT, GANGNAM_LON);
		registerIfAbsent(gridId);

		int affected = registerIfAbsent(gridId);

		assertThat(affected).isZero();
		assertThat(gridRepository.count()).isEqualTo(1);
	}

	@Test
	void center_geom은_원본_좌표를_포함한다() {
		String gridId = GridEncoder.encode(GANGNAM_LAT, GANGNAM_LON);
		registerIfAbsent(gridId);

		// 축 순서(경도, 위도) 뒤집힘 검출 — bbox가 원본 좌표와 중심점을 실제로 포함해야 한다
		Boolean containsOrigin = jdbcTemplate.queryForObject("""
			SELECT ST_Contains(bbox_geom::geometry, ST_SetSRID(ST_MakePoint(?, ?), 4326))
			FROM grids WHERE grid_id = ?
			""", Boolean.class, GANGNAM_LON, GANGNAM_LAT, gridId);
		Boolean containsCenter = jdbcTemplate.queryForObject("""
			SELECT ST_Contains(bbox_geom::geometry, center_geom::geometry)
			FROM grids WHERE grid_id = ?
			""", Boolean.class, gridId);

		assertThat(containsOrigin).isTrue();
		assertThat(containsCenter).isTrue();
	}

	@Test
	void bbox_geom은_경도위도_순서로_저장된다() {
		String gridId = GridEncoder.encode(GANGNAM_LAT, GANGNAM_LON);
		registerIfAbsent(gridId);

		Double centerX = jdbcTemplate.queryForObject(
			"SELECT ST_X(center_geom::geometry) FROM grids WHERE grid_id = ?", Double.class, gridId);
		Double centerY = jdbcTemplate.queryForObject(
			"SELECT ST_Y(center_geom::geometry) FROM grids WHERE grid_id = ?", Double.class, gridId);
		Double bboxCentroidX = jdbcTemplate.queryForObject(
			"SELECT ST_X(ST_Centroid(bbox_geom::geometry)) FROM grids WHERE grid_id = ?", Double.class, gridId);
		Double bboxCentroidY = jdbcTemplate.queryForObject(
			"SELECT ST_Y(ST_Centroid(bbox_geom::geometry)) FROM grids WHERE grid_id = ?", Double.class, gridId);

		assertThat(centerX).isBetween(126.0, 128.0); // X = 경도 (≈127)
		assertThat(centerY).isBetween(36.0, 38.0); // Y = 위도 (≈37)
		assertThat(bboxCentroidX).isBetween(126.0, 128.0);
		assertThat(bboxCentroidY).isBetween(36.0, 38.0);
	}

	@Test
	void bbox_geom은_닫힌_링_폴리곤이다() {
		String gridId = GridEncoder.encode(GANGNAM_LAT, GANGNAM_LON);
		registerIfAbsent(gridId);

		Integer numPoints = jdbcTemplate.queryForObject(
			"SELECT ST_NPoints(bbox_geom::geometry) FROM grids WHERE grid_id = ?", Integer.class, gridId);
		Boolean closed = jdbcTemplate.queryForObject(
			"SELECT ST_IsClosed(ST_ExteriorRing(bbox_geom::geometry)) FROM grids WHERE grid_id = ?",
			Boolean.class, gridId);
		Boolean valid = jdbcTemplate.queryForObject(
			"SELECT ST_IsValid(bbox_geom::geometry) FROM grids WHERE grid_id = ?", Boolean.class, gridId);

		assertThat(numPoints).isEqualTo(5);
		assertThat(closed).isTrue();
		assertThat(valid).isTrue();
	}

	private int registerIfAbsent(String gridId) {
		GridEncoder.GridIndex index = GridEncoder.decode(gridId);
		GridEncoder.GridPoint center = GridEncoder.center(gridId);
		return gridRepository.registerIfAbsent(gridId, index.gridY(), index.gridX(),
			center.lat(), center.lon(), GridWkt.bboxPolygon(gridId));
	}
}
