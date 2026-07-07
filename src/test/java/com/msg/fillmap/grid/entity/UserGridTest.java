package com.msg.fillmap.grid.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.junit.jupiter.api.Test;

class UserGridTest {

	@Test
	void UserGrid는_user_id와_grid_id_조합에_유니크_제약을_가진다() {
		Table table = UserGrid.class.getAnnotation(Table.class);

		assertThat(table.name()).isEqualTo("user_grids");
		assertThat(table.uniqueConstraints()).hasSize(1);

		UniqueConstraint constraint = table.uniqueConstraints()[0];
		assertThat(constraint.columnNames()).containsExactly("user_id", "grid_id");
	}

	@Test
	void UserGrid_저장시_video_count_기본값은_1이다() {
		UserGrid userGrid = UserGrid.builder()
			.userId(1L)
			.gridId("41642_110458")
			.build();

		assertThat(userGrid.getVideoCount()).isEqualTo(1);
	}

	@Test
	void cover_video_id는_null을_허용한다() throws NoSuchFieldException {
		UserGrid userGrid = UserGrid.builder()
			.userId(1L)
			.gridId("41642_110458")
			.build();

		assertThat(userGrid.getCoverVideoId()).isNull();

		Column column = UserGrid.class.getDeclaredField("coverVideoId").getAnnotation(Column.class);
		assertThat(column.nullable()).isTrue();
	}
}
