package com.msg.fillmap.zone.service;

/**
 * 격자 표시명의 구역 부분 (MSG-341) — 구역 이름(zones.name, 예 "서면")과 구역 내 위치 코드
 * ("{행}-{열}", 예 "A-14") 쌍이다. 두 값은 항상 쌍이라 하나만 null 인 상태가 없다.
 * 구역 밖 격자는 {@link #NONE} 이므로 소비처는 null 분기 없이 두 게터를 그대로 응답 DTO 에 옮긴다.
 * 행정동 폴백 문자열 조립은 서버가 하지 않는다 — 클라이언트가 zoneName 유무로 분기한다.
 */
public record ZoneCellName(String zoneName, String zoneCell) {

	/** 어느 구역 사각형에도 안 드는 격자의 결과 (두 필드 null). */
	public static final ZoneCellName NONE = new ZoneCellName(null, null);
}
