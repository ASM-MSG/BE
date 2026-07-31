package com.msg.fillmap.mission.seed;

import java.util.List;

/**
 * courses-seed.json 코스 1건에서 추출한 적재 입력 (MSG-225 D6). pathJson 은 GeoJSON LineString 원문
 * (jsonb 그대로 저장·와이어 passthrough), spots 는 seq 오름차순 정렬 상태다. crsIdx 는 미적재 —
 * 산출물 추적·유니크 검증용. spots[].name/method 도 미적재(운영 검수용, MSG-222 계약에 없음).
 */
public record CourseRecord(String crsIdx, String title, String pathJson, List<Spot> spots) {

	/** 판정용 포토스팟 하나 — seq 1..N 연속, gridId 는 논리 식별자("{grid_y}_{grid_x}"). */
	public record Spot(int seq, String gridId) {
	}
}
