package com.msg.fillmap.mission.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.mission.entity.Mission;

/**
 * 미션 조회 리포지토리 (MSG-222, read 전용). 활성 판정만 담당하고 mission_grids 는 MissionGridRepository 로 분리해
 * 2쿼리로 N+1 을 피한다(§도메인 1).
 */
public interface MissionRepository extends JpaRepository<Mission, Long> {

	/**
	 * 활성 미션 조회 (§도메인 2). start_at/end_at 을 각 경계 독립으로 판정한다 — NULL 은 상시로 해석하되
	 * start 전 미래 이벤트는 노출하지 않는다. 양쪽 NULL(코스·지속)은 항상 활성.
	 */
	@Query("""
		SELECT m FROM Mission m
		WHERE (m.startAt IS NULL OR m.startAt <= :now)
		  AND (m.endAt IS NULL OR :now <= m.endAt)
		""")
	List<Mission> findActive(@Param("now") LocalDateTime now);
}
