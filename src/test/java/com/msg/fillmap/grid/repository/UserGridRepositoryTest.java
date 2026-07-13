package com.msg.fillmap.grid.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.msg.fillmap.grid.AbstractPostgisTest;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridWkt;

class UserGridRepositoryTest extends AbstractPostgisTest {

	private static final String GRID_ID = GridEncoder.encode(37.4979, 127.0276);

	@Autowired
	private UserGridRepository userGridRepository;

	@Autowired
	private GridRepository gridRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long userId;

	@BeforeEach
	void setUp() {
		userId = insertUser("occupant@fillmap.kr", "oid-occupant");
		registerGrid(GRID_ID);
	}

	@Test
	void 첫_업로드는_첫_점령으로_판정된다() {
		int videoCount = userGridRepository.upsertOccupation(userId, GRID_ID);

		assertThat(videoCount).isEqualTo(1);
	}

	@Test
	void 재방문은_첫_점령이_아니다() {
		userGridRepository.upsertOccupation(userId, GRID_ID);

		int videoCount = userGridRepository.upsertOccupation(userId, GRID_ID);

		assertThat(videoCount).isEqualTo(2);
	}

	@Test
	void 재방문시_video_count가_증가한다() {
		userGridRepository.upsertOccupation(userId, GRID_ID);
		userGridRepository.upsertOccupation(userId, GRID_ID);
		userGridRepository.upsertOccupation(userId, GRID_ID);

		Integer stored = jdbcTemplate.queryForObject(
			"SELECT video_count FROM user_grids WHERE user_id = ? AND grid_id = ?",
			Integer.class, userId, GRID_ID);

		assertThat(stored).isEqualTo(3);
	}

	@Test
	void 재방문시_last_uploaded_at이_갱신된다() {
		// now()는 트랜잭션 타임스탬프라 같은 테스트 트랜잭션 안에서 값이 같다 — 과거로 되돌려 두고 갱신을 관찰한다
		userGridRepository.upsertOccupation(userId, GRID_ID);
		backdate("last_uploaded_at");
		Timestamp backdated = selectTimestamp("last_uploaded_at");

		userGridRepository.upsertOccupation(userId, GRID_ID);

		assertThat(selectTimestamp("last_uploaded_at")).isAfter(backdated);
	}

	@Test
	void 재방문해도_user_grids_row는_하나다() {
		userGridRepository.upsertOccupation(userId, GRID_ID);
		userGridRepository.upsertOccupation(userId, GRID_ID);

		assertThat(userGridRepository.count()).isEqualTo(1);
	}

	@Test
	void 재방문해도_first_collected_at은_변하지_않는다() {
		userGridRepository.upsertOccupation(userId, GRID_ID);
		backdate("first_collected_at");
		Timestamp backdated = selectTimestamp("first_collected_at");

		userGridRepository.upsertOccupation(userId, GRID_ID);

		assertThat(selectTimestamp("first_collected_at")).isEqualTo(backdated);
	}

	@Test
	void 다른_사용자는_같은_격자에_각자_점령한다() {
		Long otherUserId = insertUser("other@fillmap.kr", "oid-other");

		int first = userGridRepository.upsertOccupation(userId, GRID_ID);
		int second = userGridRepository.upsertOccupation(otherUserId, GRID_ID);

		assertThat(first).isEqualTo(1);
		assertThat(second).isEqualTo(1);
		assertThat(userGridRepository.count()).isEqualTo(2);
	}

	@Test
	void cover_video_id는_null로_저장된다() {
		userGridRepository.upsertOccupation(userId, GRID_ID);

		Long coverVideoId = jdbcTemplate.queryForObject(
			"SELECT cover_video_id FROM user_grids WHERE user_id = ? AND grid_id = ?",
			Long.class, userId, GRID_ID);

		assertThat(coverVideoId).isNull();
	}

	private Long insertUser(String email, String oid) {
		return jdbcTemplate.queryForObject(
			"INSERT INTO users (provider, oid, email, nickname) VALUES ('KAKAO', ?, ?, 'tester') RETURNING id",
			Long.class, oid, email);
	}

	private void registerGrid(String gridId) {
		GridEncoder.GridIndex index = GridEncoder.decode(gridId);
		GridEncoder.GridPoint center = GridEncoder.center(gridId);
		gridRepository.registerIfAbsent(gridId, index.gridY(), index.gridX(),
			center.lat(), center.lon(), GridWkt.bboxPolygon(gridId));
	}

	private void backdate(String column) {
		jdbcTemplate.update(
			"UPDATE user_grids SET " + column + " = now() - interval '1 hour' WHERE user_id = ? AND grid_id = ?",
			userId, GRID_ID);
	}

	private Timestamp selectTimestamp(String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM user_grids WHERE user_id = ? AND grid_id = ?",
			Timestamp.class, userId, GRID_ID);
	}
}
