package com.msg.fillmap.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgisContainerSmokeTest extends AbstractPostgisTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 컨테이너가_뜨고_Flyway_마이그레이션이_적용된다() {
		String version = jdbcTemplate.queryForObject("SELECT postgis_version()", String.class);
		Long gridCount = jdbcTemplate.queryForObject("SELECT count(*) FROM grids", Long.class);

		assertThat(version).isNotBlank();
		assertThat(gridCount).isZero();
	}
}
