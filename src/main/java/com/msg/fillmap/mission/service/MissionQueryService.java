package com.msg.fillmap.mission.service;

import java.util.List;

import com.msg.fillmap.mission.dto.MissionResponseDto;

/**
 * 활성 미션 조회 (MSG-222). active 판정 → 유형별 shape 합성 → 1h 전역 캐시. per-user 필드가 없어
 * 모든 사용자에게 동일한 스냅샷을 공유한다(§도메인 4).
 */
public interface MissionQueryService {

	/** 지금 활성인 미션 전부를 유형별 렌더 shape 로 반환한다(active 없으면 빈 리스트). */
	List<MissionResponseDto> getActiveMissions();
}
