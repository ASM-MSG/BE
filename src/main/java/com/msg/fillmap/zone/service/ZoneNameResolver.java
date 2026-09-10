package com.msg.fillmap.zone.service;

import java.util.Comparator;
import java.util.List;

import com.msg.fillmap.zone.entity.Zone;

/**
 * 한 요청 동안 고정된 구역 스냅샷 (MSG-341 D-1). 생성 시 구역을 priority 내림차순·zoneKey 사전순으로
 * 정렬해 두고, 격자마다 첫 매칭 사각형으로 표시명을 산술한다 — 정렬 1회로 겹침 타이브레이크가 해소되고
 * 격자당 비용은 최대 구역 수만큼의 정수 비교라 상수다. DB 에 가지 않는 순수 계산이라 목록 응답이
 * 항목 수만큼 불러도 쿼리가 늘지 않는다 (FR-8).
 * 명명 규칙(MSG-234 §D2 불변, 실행 주체만 서버로): 행 문자 = 'A' + (maxGridY − gridY) 라 A 가 사각형 북단,
 * 열 번호 = gridX − minGridX + 1 이라 1 이 서단이다. 규칙의 실행형 정본은 픽스처
 * {@code src/test/resources/fixtures/zone-naming.json} 이고 {@code ZoneNamingContractTest} 가 이 클래스로 검증한다.
 */
public final class ZoneNameResolver {

	private final List<Zone> zones;

	public ZoneNameResolver(List<Zone> zones) {
		this.zones = zones.stream()
			.sorted(Comparator.comparing(Zone::getPriority, Comparator.reverseOrder())
				.thenComparing(Zone::getZoneKey))
			.toList();
	}

	/** 매칭 사각형이 없으면 {@link ZoneCellName#NONE} 을 반환한다 (null 아님). */
	public ZoneCellName name(long gridY, long gridX) {
		for (Zone zone : zones) {
			if (contains(zone, gridY, gridX)) {
				// 남북 26행 캡 CHECK(zones)가 행 문자의 Z 초과를 막는다.
				char row = (char) ('A' + zone.getMaxGridY() - gridY);
				long column = gridX - zone.getMinGridX() + 1;
				return new ZoneCellName(zone.getName(), row + "-" + column);
			}
		}
		return ZoneCellName.NONE;
	}

	private static boolean contains(Zone zone, long gridY, long gridX) {
		return gridY >= zone.getMinGridY() && gridY <= zone.getMaxGridY()
			&& gridX >= zone.getMinGridX() && gridX <= zone.getMaxGridX();
	}
}
