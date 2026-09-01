package com.msg.fillmap.event.seed;

import java.util.List;

/**
 * seed/events.json 한 항목 = 행사 시리즈 하나 (MSG-438). 시리즈 → 회차 → 위치 → 사각형의 계층 구조다.
 * <p>
 * 시각을 {@code String} 으로 받는 이유: 시드 작성자는 KST 로 생각하는데 naive 표기를 허용하면
 * "naive = UTC" 관례(MSG-376)와 충돌해 9시간 어긋난 잠복 버그가 된다. 시더가 OffsetDateTime 으로
 * 엄격 파싱하므로 오프셋 없는 표기는 기동을 멈춘다.
 * <p>
 * visible_from 은 시드 입력이 아니다 — 시더가 항상 startsAt - 14일로 계산하므로 필드 자체를 두지 않는다.
 */
public record EventSeed(String seriesKey, String seriesName, List<Occurrence> occurrences) {

	public record Occurrence(String occurrenceKey, String title, String cityName, String startsAt, String endsAt,
		Rect exposure, String imageKey, List<Location> locations) {
	}

	public record Location(String locationKey, String name, String type, String operatingHours, Integer displayOrder,
		List<Rect> areaRects, String representativeGridId, String imageKey) {
	}

	/** 격자 인덱스 정수 사각형 (양끝 포함). 회차의 노출 영역과 위치의 영역 사각형이 같은 형태를 쓴다. */
	public record Rect(Integer minGridY, Integer maxGridY, Integer minGridX, Integer maxGridX) {
	}
}
