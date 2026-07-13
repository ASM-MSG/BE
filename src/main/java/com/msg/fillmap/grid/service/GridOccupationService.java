package com.msg.fillmap.grid.service;

import com.msg.fillmap.grid.dto.OccupationResult;

/**
 * 격자 write 계약 (Owner A 제공 → Owner B 소비, MSG-66이 호출).
 * 영상 저장 트랜잭션 안에서 호출되어 전역 격자 등록 + 개인 점령/재방문을 원자적으로 반영한다.
 */
public interface GridOccupationService {

	/**
	 * 영상 업로드 좌표를 격자에 반영한다.
	 * 전역 격자 등록(lazy insert) + 개인 점령/재방문 판정을 수행한다. 호출자(MSG-66)의 트랜잭션에 참여한다.
	 *
	 * @param videoId 대표 영상 자동 지정(Q1)을 위해 시그니처에 열어두는 자리 — 본 티켓에서는 사용하지 않는다(D8).
	 * @return 점령 결과 (grid_id, 첫 점령 여부, UPSERT 후 video_count)
	 */
	OccupationResult occupy(Long userId, double lat, double lon, Long videoId);
}
