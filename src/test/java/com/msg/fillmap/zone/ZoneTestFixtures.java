package com.msg.fillmap.zone;

import com.msg.fillmap.zone.entity.Zone;

/**
 * zone 테스트 공용 fixture. 공유 로컬 DB 오염 방지 — 합성 zone_key/이름은 "m234" 접두(실존 상권명 무충돌),
 * 격자 대역은 기존 테스트(39500·39550·39600·39700·40000)와 무충돌한 45xxx/112xxx 를 쓴다. 정리는 @Transactional 롤백.
 */
public final class ZoneTestFixtures {

	public static final String KEY_PREFIX = "m234-";
	public static final String NAME_PREFIX = "m234";

	public static final int MIN_GRID_Y = 45100;
	public static final int MAX_GRID_Y = 45110;
	public static final int MIN_GRID_X = 112200;
	public static final int MAX_GRID_X = 112220;

	private ZoneTestFixtures() {
	}

	/** 기본 대역(45xxx/112xxx) 합성 zone. region_code 는 FK 충돌 회피를 위해 null. */
	public static Zone zone(String keySuffix, String nameSuffix, int priority) {
		return zone(keySuffix, nameSuffix, MIN_GRID_Y, MAX_GRID_Y, MIN_GRID_X, MAX_GRID_X, priority);
	}

	public static Zone zone(String keySuffix, String nameSuffix,
		int minGridY, int maxGridY, int minGridX, int maxGridX, int priority) {
		return Zone.builder()
			.zoneKey(KEY_PREFIX + keySuffix)
			.name(NAME_PREFIX + nameSuffix)
			.minGridY(minGridY)
			.maxGridY(maxGridY)
			.minGridX(minGridX)
			.maxGridX(maxGridX)
			.priority(priority)
			.build();
	}
}
