package com.msg.fillmap.global.geo;

import java.util.Set;

/**
 * 행사 위치의 대표 격자 3단 결정 (MSG-438, FR-EVENT-08 · PRD §4.1). 시딩 시점에 계산해 저장하고
 * 조회·업로드는 저장값만 읽는다.
 * <p>
 * 입력이 시드의 사각형 목록이 아니라 <b>전개된 격자 집합</b>인 것이 핵심이다 — 같은 영역을 어떤 사각형
 * 조합으로 그렸든 집합이 같으면 결과가 같아야 하므로 판정을 표현이 아니라 기하에 건다.
 * <p>
 * 세 경로 모두 결정적이라 매 시딩 재계산이 곧 멱등이다.
 * <p>
 * MSG-459 부터 축제·팝업 미션의 대표 격자도 이 규칙 하나를 쓴다 — 그래서 자리가 event.seed 가 아니라
 * 어느 도메인에도 속하지 않는 global.geo 다(MSG-459 D-1). 로직은 이동 시점에 바뀌지 않았다.
 */
public final class RepresentativeGridResolver {

	private RepresentativeGridResolver() {
	}

	/**
	 * @param cells        전개된 영역 격자 집합 (비어 있으면 안 됨)
	 * @param designated   시드의 운영자 지정 대표 격자 id, 없으면 null
	 * @return {@code "{gridY}_{gridX}"} 격자 id
	 */
	public static String resolve(Set<AreaCell> cells, String designated) {
		if (cells.isEmpty()) {
			throw new IllegalStateException("행사 위치 영역이 비어 있어 대표 격자를 정할 수 없습니다");
		}

		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		for (AreaCell cell : cells) {
			minY = Math.min(minY, cell.gridY());
			maxY = Math.max(maxY, cell.gridY());
			minX = Math.min(minX, cell.gridX());
			maxX = Math.max(maxX, cell.gridX());
		}
		long rows = (long) maxY - minY + 1;
		long columns = (long) maxX - minX + 1;

		// 1. 홀수 직사각형 정중앙. 집합 크기가 경계 상자를 꽉 채우면 직사각형이다(표현 무관 판정).
		if (cells.size() == rows * columns && rows % 2 == 1 && columns % 2 == 1) {
			// min+max 가 짝수라 정수 나눗셈이 정확히 떨어진다.
			String center = new AreaCell((minY + maxY) / 2, (minX + maxX) / 2).gridId();
			if (designated != null && !designated.equals(center)) {
				// 조용히 무시하면 시드 파일과 저장값이 어긋난 채 남는다 (FR-4 는 정중앙을 무조건으로 요구).
				throw new IllegalStateException(
					"홀수 직사각형 영역의 대표 격자는 정중앙 %s 인데 시드 지정값이 %s 입니다".formatted(center, designated));
			}
			return center;
		}

		// 2. 운영자 지정. 지연 FK 가 최종 방어선이지만 위반 메시지가 읽기 어려워 시더가 먼저 막는다.
		if (designated != null) {
			if (cells.stream().noneMatch(cell -> cell.gridId().equals(designated))) {
				throw new IllegalStateException("지정한 대표 격자 %s 가 행사 위치 영역 밖입니다".formatted(designated));
			}
			return designated;
		}

		// 3. 영역 중심 최근접.
		return nearestToCentroid(cells);
	}

	/**
	 * 중심 {@code (Sy/n, Sx/n)} 과의 거리 제곱에 {@code n²} 을 곱한 동치식
	 * {@code (n·gy - Sy)² + (n·gx - Sx)²} 을 최소화하는 격자. long 정수 산술만 쓰므로 부동소수점 오차가 없다
	 * (인덱스 상한 100,000 · 셀 수 상한 2,500 에서 최대 약 2.5e17 이라 long 범위 안이다).
	 * 동률이면 gridY, 그다음 gridX 가 작은 것(남서 우선)으로 결정성을 확보한다.
	 */
	private static String nearestToCentroid(Set<AreaCell> cells) {
		long n = cells.size();
		long sumY = 0;
		long sumX = 0;
		for (AreaCell cell : cells) {
			sumY += cell.gridY();
			sumX += cell.gridX();
		}

		AreaCell best = null;
		long bestDistance = Long.MAX_VALUE;
		for (AreaCell cell : cells) {
			long dy = n * cell.gridY() - sumY;
			long dx = n * cell.gridX() - sumX;
			long distance = dy * dy + dx * dx;
			if (distance < bestDistance || (distance == bestDistance && isSouthWestOf(cell, best))) {
				best = cell;
				bestDistance = distance;
			}
		}
		return best.gridId();
	}

	private static boolean isSouthWestOf(AreaCell candidate, AreaCell current) {
		if (candidate.gridY() != current.gridY()) {
			return candidate.gridY() < current.gridY();
		}
		return candidate.gridX() < current.gridX();
	}
}
