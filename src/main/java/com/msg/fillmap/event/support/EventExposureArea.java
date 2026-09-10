package com.msg.fillmap.event.support;

import java.util.Collection;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.global.geo.AreaCell;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;

/**
 * 회차 노출 영역의 경계 사각형 산술 (MSG-500 D-8·D-3). "노출 영역 = 위치 사각형들을 감싸는 범위"라는
 * 불변식을 <b>세 경로가 같은 규칙으로</b> 유지한다: 참여형 승인의 확장, 중지의 재계산, 그리고 재시드다.
 * 세 곳이 각자 min/max 를 접으면 한 곳만 고쳐졌을 때 같은 회차가 경로마다 다른 범위를 갖는다.
 *
 * <p>세 경로의 <b>입력</b>은 다르고 그 차이가 곧 정책이다.
 * <ul>
 *   <li>승인: 기존 영역 ∪ 새 위치 셀 — 넓히기만 한다.</li>
 *   <li>중지: 남은 <b>가시</b> 위치 격자만 — 숨긴 위치가 커버하던 범위를 되돌린다(시드 값 아래로도 줄어든다).</li>
 *   <li>재시드: 시드 사각형 ∪ 가시 위치 격자 — <b>시드가 바닥값</b>이라 중지와 달리 그 밑으로는 안 줄어든다.
 *       시더는 시드 파일이 정본인 값을 쓰는 자리이고, 승인이 넓힌 몫만 얹는다.</li>
 * </ul>
 */
public record EventExposureArea(int minGridY, int maxGridY, int minGridX, int maxGridX) {

	public static EventExposureArea of(int minGridY, int maxGridY, int minGridX, int maxGridX) {
		return new EventExposureArea(minGridY, maxGridY, minGridX, maxGridX);
	}

	/** 회차의 현재 영역. */
	public static EventExposureArea of(EventOccurrence occurrence) {
		return new EventExposureArea(occurrence.getMinGridY(), occurrence.getMaxGridY(),
			occurrence.getMinGridX(), occurrence.getMaxGridX());
	}

	/** 격자 id 집합의 경계 — 비어 있으면 null 이다(감쌀 것이 없으면 영역도 없다). */
	public static EventExposureArea ofGridIds(Collection<String> gridIds) {
		EventExposureArea area = null;
		for (String gridId : gridIds) {
			GridIndex index = GridEncoder.decode(gridId);
			area = expand(area, (int) index.gridY(), (int) index.gridX());
		}
		return area;
	}

	/** 셀 집합의 경계 — 비어 있으면 null 이다. */
	public static EventExposureArea ofCells(Collection<AreaCell> cells) {
		EventExposureArea area = null;
		for (AreaCell cell : cells) {
			area = expand(area, cell.gridY(), cell.gridX());
		}
		return area;
	}

	/** 합집합의 경계 — {@code other} 가 null 이면 자기 자신이다(감쌀 것이 늘지 않았다). */
	public EventExposureArea union(EventExposureArea other) {
		if (other == null) {
			return this;
		}
		return new EventExposureArea(
			Math.min(minGridY, other.minGridY()), Math.max(maxGridY, other.maxGridY()),
			Math.min(minGridX, other.minGridX()), Math.max(maxGridX, other.maxGridX()));
	}

	private static EventExposureArea expand(EventExposureArea area, int gridY, int gridX) {
		if (area == null) {
			return new EventExposureArea(gridY, gridY, gridX, gridX);
		}
		return new EventExposureArea(
			Math.min(area.minGridY(), gridY), Math.max(area.maxGridY(), gridY),
			Math.min(area.minGridX(), gridX), Math.max(area.maxGridX(), gridX));
	}
}
