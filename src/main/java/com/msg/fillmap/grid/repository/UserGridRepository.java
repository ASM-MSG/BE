package com.msg.fillmap.grid.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.grid.entity.UserGrid;
import com.msg.fillmap.grid.entity.UserGridId;

public interface UserGridRepository extends JpaRepository<UserGrid, UserGridId> {

	/**
	 * 개인 점령/재방문을 DB 왕복 1회로 UPSERT하고 UPSERT 후의 video_count를 돌려준다.
	 * 첫 INSERT면 1, 충돌 UPDATE면 2 이상이므로 <b>반환값 == 1 ⟺ 첫 점령</b>이다.
	 * <p>
	 * data-modifying CTE를 최상위 SELECT로 감싼다 — 문장 자체가 SELECT라 Spring Data가 결과셋 쿼리로
	 * 실행해 RETURNING 스칼라를 그대로 받는다. ({@code @Modifying}을 붙이면 RETURNING 값이 아니라
	 * 영향 row 수가 오므로 붙이지 않는다. RETURNING만 쓰고 엔티티를 재조회하지 않으므로
	 * {@code clearAutomatically}도 불필요하다.)
	 *
	 * @return UPSERT 후의 video_count (1 = 첫 점령, ≥2 = 재방문)
	 */
	@Query(value = """
		WITH upserted AS (
			INSERT INTO user_grids (user_id, grid_id, video_count)
			VALUES (:userId, :gridId, 1)
			ON CONFLICT (user_id, grid_id) DO UPDATE
			   SET video_count = user_grids.video_count + 1,
			       last_uploaded_at = now()
			RETURNING video_count
		)
		SELECT video_count FROM upserted
		""", nativeQuery = true)
	int upsertOccupation(@Param("userId") Long userId, @Param("gridId") String gridId);
}
