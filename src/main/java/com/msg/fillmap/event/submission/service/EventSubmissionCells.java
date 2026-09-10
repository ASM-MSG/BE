package com.msg.fillmap.event.submission.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.msg.fillmap.event.submission.entity.EventSubmissionAreaRect;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.global.geo.AreaCell;

/**
 * 저장된 신청 사각형을 격자 집합으로 펼치는 한 곳 (MSG-498 D-7 합집합 판정의 재사용). 겹치는 사각형을
 * Set 이 자연히 한 번만 남기므로 "표현이 아니라 기하로 결정된다"는 성질이 상세 표시(칸 수)와 승인
 * 산출물(판정 격자)에서 같게 성립한다 — 두 곳이 서로 다른 방식으로 세면 콘솔이 보여준 칸 수와 실제로
 * 깔린 격자가 어긋난다.
 *
 * <p>저장된 사각형은 접수 검증(위치당 81칸 상한·인덱스 범위)을 이미 통과했으므로 여기서는 다시 검증하지
 * 않는다 — 검증은 입구인 접수 경로 하나가 맡는다.
 */
final class EventSubmissionCells {

	private EventSubmissionCells() {
	}

	/** 위치 하나의 셀 집합. */
	static Set<AreaCell> of(EventSubmissionLocation location) {
		Set<AreaCell> cells = new LinkedHashSet<>();
		for (EventSubmissionAreaRect rect : location.getRects()) {
			for (int gridY = rect.getMinGridY(); gridY <= rect.getMaxGridY(); gridY++) {
				for (int gridX = rect.getMinGridX(); gridX <= rect.getMaxGridX(); gridX++) {
					cells.add(new AreaCell(gridY, gridX));
				}
			}
		}
		return cells;
	}

	/** 전 위치의 셀 합집합 — 위치끼리 겹쳐도 한 번만 남는다(승인 미션의 판정 격자, MSG-500 D-2). */
	static Set<AreaCell> union(List<EventSubmissionLocation> locations) {
		Set<AreaCell> cells = new LinkedHashSet<>();
		for (EventSubmissionLocation location : locations) {
			cells.addAll(of(location));
		}
		return cells;
	}
}
