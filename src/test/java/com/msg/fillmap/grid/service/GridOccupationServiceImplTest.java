package com.msg.fillmap.grid.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.msg.fillmap.grid.AbstractPostgisTest;
import com.msg.fillmap.grid.dto.OccupationResult;
import com.msg.fillmap.grid.service.impl.GridOccupationServiceImpl;

@Import(GridOccupationServiceImpl.class)
class GridOccupationServiceImplTest extends AbstractPostgisTest {

	private static final double GANGNAM_LAT = 37.4979;
	private static final double GANGNAM_LON = 127.0276;

	@Autowired
	private GridOccupationService gridOccupationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long userId;

	@BeforeEach
	void setUp() {
		userId = insertUser("occupant@fillmap.kr", "oid-occupant");
	}

	@Test
	void 같은_격자_내_서로_다른_좌표는_동일한_격자를_점령한다() {
		OccupationResult first = gridOccupationService.occupy(userId, GANGNAM_LAT, GANGNAM_LON, null);
		OccupationResult second = gridOccupationService.occupy(userId, GANGNAM_LAT + 0.0001, GANGNAM_LON + 0.0001, null);

		assertThat(second.gridId()).isEqualTo(first.gridId());
	}

	@Test
	void 미점령_격자에_업로드하면_isFirstOccupation은_true다() {
		OccupationResult result = gridOccupationService.occupy(userId, GANGNAM_LAT, GANGNAM_LON, null);

		assertThat(result.isFirstOccupation()).isTrue();
		assertThat(result.videoCount()).isEqualTo(1);
	}

	@Test
	void 점령한_격자에_재업로드하면_isFirstOccupation은_false다() {
		gridOccupationService.occupy(userId, GANGNAM_LAT, GANGNAM_LON, null);

		OccupationResult result = gridOccupationService.occupy(userId, GANGNAM_LAT, GANGNAM_LON, null);

		assertThat(result.isFirstOccupation()).isFalse();
		assertThat(result.videoCount()).isEqualTo(2);
	}

	@Test
	void 전역_격자_등록이_개인_점령보다_먼저_일어난다() {
		// user_grids.grid_id → grids.grid_id FK 위반 없이 성공하면 등록이 점령보다 먼저 일어난 것
		OccupationResult result = gridOccupationService.occupy(userId, GANGNAM_LAT, GANGNAM_LON, null);

		Long gridRows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM grids WHERE grid_id = ?", Long.class, result.gridId());
		assertThat(gridRows).isEqualTo(1);
	}

	@Test
	void 이미_다른_사용자가_등록한_격자에_첫_업로드하면_isFirstOccupation은_true다() {
		Long otherUserId = insertUser("other@fillmap.kr", "oid-other");
		gridOccupationService.occupy(otherUserId, GANGNAM_LAT, GANGNAM_LON, null);

		OccupationResult result = gridOccupationService.occupy(userId, GANGNAM_LAT, GANGNAM_LON, null);

		assertThat(result.isFirstOccupation()).isTrue();
		assertThat(result.videoCount()).isEqualTo(1);
	}

	private Long insertUser(String email, String oid) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO users (provider, oid, email, nickname) VALUES ('KAKAO', ?, ?, 'tester') RETURNING id",
			Long.class, oid, email);
	}
}
