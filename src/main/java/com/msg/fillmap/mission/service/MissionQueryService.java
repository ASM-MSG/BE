package com.msg.fillmap.mission.service;

import java.util.List;

import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.dto.MissionProgressResponseDto;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.MissionType;

/**
 * 미션 조회 (MSG-222 활성 조회 → MSG-398 뷰포트 자르기·진행도). 목록은 active 판정 → 유형별 shape 합성 →
 * 1h 전역 스냅샷 캐시 위 메모리 필터(D1), 진행도는 캐시 없이 조회 시점 집계다.
 */
public interface MissionQueryService {

	/**
	 * 뷰포트 안의 활성 미션 목록 (MSG-398). 전국 스냅샷을 종류와 뷰포트로 걸러 낸다.
	 * 응답에 사용자별 값이 없어 모든 사용자에게 같다(FR-MISSION-02). userId 를 받지 않는 것이 그 계약의 방어다.
	 * 뒤집힌 bbox 는 INVALID_VIEWPORT, 한 변 0.5도 초과는 VIEWPORT_TOO_LARGE.
	 */
	List<MissionResponseDto> getMissionsInViewport(ViewportBounds bounds, MissionType type);

	/**
	 * 미션별 내 진행도와 스탬프 보유 여부 (MSG-398). 진행도는 스탬프 판정과 같은 술어다 — 미션 기간
	 * 안에 촬영한 내 영상(status &lt;&gt; 'DELETED')이 있는 격자 수를 조회 시점에 세며 별도 집계 값을 두지
	 * 않는다(FR-MISSION-18, D8). 존재하지 않는 missionId 는 결과에서 빠지고,
	 * 기간이 끝난 미션도 조회된다. 빈 입력은 빈 결과다(예외 아님).
	 */
	List<MissionProgressResponseDto> getMyProgress(long userId, List<Long> missionIds);
}
