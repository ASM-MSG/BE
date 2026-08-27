package com.msg.fillmap.mission.seed;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.geo.KoreaCoordinates;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 코스 포토스팟의 최종 표시 이름을 정한다 (MSG-492 §도메인 1). <b>적재 시점 1회</b>만 돌고 결과 문자열이
 * mission_grids.name 에 저장된다 — 조회 경로는 이 클래스를 부르지 않는다(D-2).
 *
 * <p>사다리는 처음 값이 나오는 자리에서 멈춘다.
 * <pre>
 * ① 산출물 name (method=tourapi)   → 그대로              ("광안리해수욕장")
 * ② 구역 안                        → 구역명 + " " + 칸번호 ("서면 A-14")
 * ③ 좌표를 품는 행정동              → 이름의 마지막 토큰    ("다대2동")
 * ④ 최근접 행정동                   → 이름의 마지막 토큰    ("다대2동")
 * ⑤ null
 * </pre>
 *
 * <p><b>문자열 조립을 서버가 하는 것은 이 레포 관행에서 벗어난 예외다</b>(D-7). 지금까지 서버는
 * zoneName·zoneCell·regionName 재료만 내려주고 조립은 클라이언트 몫이었다({@link ZoneCellName} 주석 명문).
 * 코스 스팟은 응답 항목이 문자열 하나라 재료 셋을 실을 자리가 없어서, 이름 하나가 필요한 자리에 이름 하나를 준다.
 * 이 조립을 다른 화면과 공유하지 않는다 — 훗날 조립본을 원하는 화면이 생기면 그때 이 함수를 공유한다.
 *
 * <p>리졸버는 시더 실행당 1회 만든다. {@link ZoneNameResolver} 가 zones 전체를 담고 있어 스팟마다 새로 만들면
 * 코스 수만큼 zones 전량 로드가 반복된다.
 */
@RequiredArgsConstructor
public class CourseSpotNameResolver {

	/** regions.region_name 은 "부산광역시 사하구 다대2동" 전체 경로라 목록 한 줄에 그대로 넣으면 넘친다. */
	private static final String REGION_NAME_DELIMITER = " ";

	private final ZoneNameResolver zoneNameResolver;
	private final RegionQueryService regionQueryService;

	/**
	 * 스팟 하나의 표시 이름. 이름이 이미 있으면 구역·행정동 판정을 돌리지 않는다 — 명소 417곳에 불필요한
	 * 공간 조회를 태우지 않기 위해서다.
	 *
	 * @param gridId  격자 논리 식별자 "{grid_y}_{grid_x}"
	 * @param seedName 산출물이 준 명소 이름 (없으면 null)
	 * @return 최종 표시 문자열, 어느 단에서도 못 구하면 null
	 */
	public String resolve(String gridId, String seedName) {
		if (seedName != null && !seedName.isBlank()) {
			return seedName;
		}
		GridEncoder.GridIndex index = GridEncoder.decode(gridId);
		ZoneCellName zoneCell = zoneNameResolver.name(index.gridY(), index.gridX());
		if (zoneCell.zoneName() != null) {
			return zoneCell.zoneName() + " " + zoneCell.zoneCell();
		}

		GridEncoder.GridPoint center = GridEncoder.center(gridId);
		if (KoreaCoordinates.isOutOfService(center.lat(), center.lon())) {
			// 행정동 조회 계약은 서비스범위 밖 좌표를 INVALID_COORDINATE 로 거절한다. 이름 하나 때문에 시드
			// 전체를 롤백시킬 이유가 없으므로 그 스팟만 이름 없이 둔다(사다리 ⑤). 산출물 path 좌표는 reader 가
			// 이미 범위 검증하지만 스팟 gridId 는 그 검증 밖이라 여기서 막는다.
			return null;
		}
		return regionQueryService.resolveByPoint(center.lat(), center.lon())
			.or(() -> regionQueryService.resolveNearestByPoint(center.lat(), center.lon()))
			.map(CourseSpotNameResolver::lastToken)
			.orElse(null);
	}

	/** "부산광역시 사하구 다대2동" → "다대2동". 공백이 없으면 원문 그대로. */
	private static String lastToken(RegionView region) {
		String name = region.regionName();
		int lastSpace = name.lastIndexOf(REGION_NAME_DELIMITER);
		return lastSpace < 0 ? name : name.substring(lastSpace + 1);
	}
}
