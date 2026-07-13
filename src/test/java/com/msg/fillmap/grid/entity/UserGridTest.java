package com.msg.fillmap.grid.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;

class UserGridTest {

	@Test
	void UserGrid는_user_id와_grid_id_복합키를_EmbeddedId로_가진다() throws NoSuchFieldException {
		Table table = UserGrid.class.getAnnotation(Table.class);

		assertThat(table.name()).isEqualTo("user_grids");
		assertThat(UserGrid.class.getDeclaredField("id").isAnnotationPresent(EmbeddedId.class)).isTrue();
		assertThat(UserGridId.class.isAnnotationPresent(Embeddable.class)).isTrue();

		Column userIdColumn = UserGridId.class.getDeclaredField("userId").getAnnotation(Column.class);
		Column gridIdColumn = UserGridId.class.getDeclaredField("gridId").getAnnotation(Column.class);
		assertThat(userIdColumn.name()).isEqualTo("user_id");
		assertThat(gridIdColumn.name()).isEqualTo("grid_id");
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
