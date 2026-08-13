package com.msg.fillmap.mission.seed;

import java.util.List;

/**
 * courses-seed.json 코스 1건에서 추출한 적재 입력 (MSG-225 D6 · MSG-383 D4). pathJson 은 GeoJSON
 * LineString 원문(jsonb 그대로 저장·와이어 passthrough), spots 는 seq 오름차순 정렬 상태다. crsIdx 는
 * 미적재 — 산출물 추적·유니크 검증용. spots[].name/method 도 미적재(운영 검수용, MSG-222 계약에 없음).
 *
 * 뒤 5개는 MSG-383 이 더한 화면용 값이다(산출물 키 contents·sigun·distanceKm·durationMinutes·level).
 * 거리는 미터로 환산해 담는다 — 원본이 킬로미터 문자열이라 정수 컬럼에 그대로 넣으면 "13.4" 가 13 으로
 * 잘린다(D2). crsSummary·crsCycle·travelerinfo 는 계속 미적재다.
 */
public record CourseRecord(String crsIdx, String title, String pathJson, List<Spot> spots,
	String description, String placeName, Integer distanceMeters, Integer durationMinutes, Integer difficulty) {

	/** 판정용 포토스팟 하나 — seq 1..N 연속, gridId 는 논리 식별자("{grid_y}_{grid_x}"). */
	public record Spot(int seq, String gridId) {
	}
}
