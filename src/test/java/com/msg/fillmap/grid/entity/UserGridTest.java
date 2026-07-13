package com.msg.fillmap.grid.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;

import org.junit.jupiter.api.Test;

class UserGridTest {

	@Test
	void UserGrid는_user_id와_grid_id_복합_기본키를_가진다() throws NoSuchFieldException {
		Field idField = UserGrid.class.getDeclaredField("id");

		assertThat(idField.getAnnotation(EmbeddedId.class)).isNotNull();
		assertThat(idField.getType()).isEqualTo(UserGridId.class);
	}

	@Test
	void 같은_user_id와_grid_id_조합의_복합키는_동등하다() {
		UserGridId id1 = new UserGridId(1L, "41642_110458");
		UserGridId id2 = new UserGridId(1L, "41642_110458");
		UserGridId other = new UserGridId(2L, "41642_110458");

		assertThat(id1).isEqualTo(id2);
		assertThat(id1).hasSameHashCodeAs(id2);
		assertThat(id1).isNotEqualTo(other);
	}

	@Test
	void UserGrid_저장시_video_count_기본값은_1이다() {
		UserGrid userGrid = UserGrid.builder()
			.userId(1L)
			.gridId("41642_110458")
			.build();

		assertThat(userGrid.getVideoCount()).isEqualTo(1);
		assertThat(userGrid.getUserId()).isEqualTo(1L);
		assertThat(userGrid.getGridId()).isEqualTo("41642_110458");
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
