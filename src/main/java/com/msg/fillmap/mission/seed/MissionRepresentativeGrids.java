package com.msg.fillmap.mission.seed;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import com.msg.fillmap.global.geo.AreaCell;
import com.msg.fillmap.global.geo.RepresentativeGridResolver;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionType;

/**
 * 시더의 대표 격자 유지 규칙 (MSG-459 D-6) — 축제·팝업 두 시더가 적재·갱신 경로에서 함께 쓴다.
 * 산출 자체는 행사방과 같은 클래스 하나({@link RepresentativeGridResolver})가 하고, 여기서는
 * <b>언제 다시 산출하는가</b>만 정한다.
 */
final class MissionRepresentativeGrids {

	/** 대표 격자를 갖는 유형 — chk_missions_rep_grid_type 과 같은 집합이어야 한다. */
	private static final Set<MissionType> ELIGIBLE_TYPES = Set.of(MissionType.EVENT, MissionType.POPUP);

	private MissionRepresentativeGrids() {
	}

	/**
	 * 대표 격자가 비어 있거나 새 판정 집합 밖일 때만 다시 산출한다. 값이 여전히 집합 안이면 손대지 않으므로
	 * 재적재가 곧 멱등이다(FR-MISSION-11).
	 * <p>
	 * 목표 칸수 1 조건이 규칙의 일부인 것은 저장 제약과 어긋나지 않기 위해서다 —
	 * {@code chk_missions_rep_grid_type} 이 그 행만 대표 격자를 갖도록 막으므로, 조건 없이 "비어 있으면
	 * 채운다"로 적으면 목표가 2 이상인 행에서 CHECK 에 걸려 <b>시더 트랜잭션 전체가 롤백된다</b>.
	 * 목표가 2 이상인 축제·팝업은 대표 격자를 비운 채 두는 것이 정상이며 그 미션은 업로드 대상이 아니다
	 * (스펙 D-9 의 보존 고리가 성립하지 않는다).
	 *
	 * @param gridIds 그 미션의 현재 판정 격자 집합
	 * @return 값을 새로 산출해 넣었으면 true (호출자가 경고 로그·건수 집계에 쓴다)
	 */
	static boolean reassignIfOutside(Mission mission, Collection<String> gridIds) {
		if (!ELIGIBLE_TYPES.contains(mission.getType()) || mission.getTargetCount() != 1 || gridIds.isEmpty()) {
			return false;
		}
		// null 을 contains 로 묻지 않는다 — List.of 로 만든 불변 목록은 그 물음에 NPE 로 답한다.
		String current = mission.getRepresentativeGridId();
		if (current != null && gridIds.contains(current)) {
			return false;
		}
		mission.assignRepresentativeGrid(RepresentativeGridResolver.resolve(cells(gridIds), null));
		return true;
	}

	/** 지정값 없이 부른다 — 미션 시드에는 운영자 지정 대표 격자 개념이 없어 리졸버 2단을 타지 않는다. */
	private static Set<AreaCell> cells(Collection<String> gridIds) {
		return gridIds.stream()
			.map(GridEncoder::decode)
			.map(index -> new AreaCell((int) index.gridY(), (int) index.gridX()))
			.collect(Collectors.toSet());
	}
}
