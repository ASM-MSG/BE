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

	/**
	 * 기간 안에 촬영한 내 영상이 있는 대상 격자 id (MSG-399 — 상세의 스팟 방문 여부). videos 조인 조건은
	 * 진행도 쿼리(MissionRepository.findProgress, MSG-398 D8)와 글자 단위로 같다 — 같은 카드에 그려지는
	 * filledCount 와 spotStats.visited 가 다른 술어에서 나오면 기간 있는 코스가 등장하는 순간 조용히
	 * 갈라진다. 행사 영상 안티조인(NOT EXISTS event_videos)도 그 동일 계약의 일부다 — 행사 영상은 현장에
	 * 없어도 올릴 수 있어 방문 증거가 아니다(MSG-450). 같은 격자에 재방문 영상이 여러 개일 수 있어
	 * DISTINCT 가 필수다. 받치는 인덱스는 findProgress 와 같은 idx_videos_user_created 의 user_id
	 * 선두부라 별도 인덱스는 추가하지 않는다.
	 */
	@Query(value = """
		SELECT DISTINCT mg.grid_id
		FROM mission_grids mg
		JOIN missions m ON m.id = mg.mission_id
		JOIN videos v ON v.grid_id = mg.grid_id
			AND v.user_id = :userId
			AND v.status <> 'DELETED'
			AND NOT EXISTS (SELECT 1 FROM event_videos ev WHERE ev.video_id = v.id)
			AND (m.start_at IS NULL OR v.recorded_at >= m.start_at)
			AND (m.end_at IS NULL OR v.recorded_at <= m.end_at)
		WHERE mg.mission_id = :missionId
		""", nativeQuery = true)
	List<String> findVisitedGridIds(@Param("missionId") long missionId, @Param("userId") long userId);
}
