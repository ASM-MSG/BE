package com.msg.fillmap.mission.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionGridId;

/**
 * 미션 격자 조회 리포지토리 (MSG-222, read 전용). 활성 미션들의 mission_grids 를 한 번에 IN 조회해
 * 서비스에서 missionId 로 그룹핑한다(N+1 회피, §도메인 1). 호출부가 빈 미션 목록이면 조회 자체를 건너뛴다.
 */
public interface MissionGridRepository extends JpaRepository<MissionGrid, MissionGridId> {

	@Query("SELECT mg FROM MissionGrid mg WHERE mg.id.missionId IN :missionIds")
	List<MissionGrid> findByMissionIds(@Param("missionIds") Collection<Long> missionIds);
}
