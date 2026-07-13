package com.msg.fillmap.grid.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;

class UserGridTest {

	@Test
	void UserGrid는_user_id와_grid_id_복합키를_가진다() throws NoSuchFieldException {
		Table table = UserGrid.class.getAnnotation(Table.class);
		IdClass idClass = UserGrid.class.getAnnotation(IdClass.class);

		assertThat(table.name()).isEqualTo("user_grids");
		assertThat(idClass.value()).isEqualTo(UserGridId.class);
		assertThat(UserGrid.class.getDeclaredField("userId").isAnnotationPresent(Id.class)).isTrue();
		assertThat(UserGrid.class.getDeclaredField("gridId").isAnnotationPresent(Id.class)).isTrue();
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
