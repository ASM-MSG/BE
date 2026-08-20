package com.msg.fillmap.mission.service;

import java.util.List;
import java.util.Set;

import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto;
import com.msg.fillmap.mission.dto.MissionProgressResponseDto;
import com.msg.fillmap.mission.dto.MissionRegionAggregateResponseDto;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.MissionType;

/**
 * 미션 조회 (MSG-222 활성 조회 → MSG-398 뷰포트 자르기·진행도 → MSG-399 상세). 목록은 active 판정 →
 * 유형별 shape 합성 → 1h 전역 스냅샷 캐시 위 메모리 필터(D1), 진행도·상세는 캐시 없이 조회 시점 집계다.
 */
public interface MissionQueryService {

	/**
	 * 행정 단위 집계가 열리는 미션 종류 (MSG-437) — 줌아웃 묶음을 그리는 축제·팝업뿐이다. 코스는 1km 화면
	 * 묶음을 유지하고 나머지 유형은 대응 칩이 없다. 컨트롤러의 type 거절(12402)과 스냅숏의 귀속 판정 대상이
	 * 반드시 같은 집합이어야 한다 — 갈라지면 컨트롤러만 연 유형이 오류 없이 늘 빈 배열로 나가는 조용한
	 * 결함이 되므로 정의를 한 곳에 둔다.
	 */
	Set<MissionType> AGGREGATABLE_TYPES = Set.of(MissionType.EVENT, MissionType.POPUP);

	/**
	 * 뷰포트 안의 활성 미션 목록 (MSG-398). 전국 스냅샷을 종류와 뷰포트로 걸러 낸다.
	 * 응답에 사용자별 값이 없어 모든 사용자에게 같다(FR-MISSION-02). userId 를 받지 않는 것이 그 계약의 방어다.
	 * 뒤집힌 bbox 는 INVALID_VIEWPORT, 한 변 0.5도 초과는 VIEWPORT_TOO_LARGE.
	 */
	List<MissionResponseDto> getMissionsInViewport(ViewportBounds bounds, MissionType type);

	/**
	 * 넓은 축척용 미션 행정 단위 집계 (MSG-437). 뷰포트 안 EVENT·POPUP 미션을 행정동 코드 접두(unit)로 묶어
	 * 지역 이름·개수·대표 좌표·미션 id 목록으로 돌려준다. 목록과 같은 1시간 스냅숏 위 메모리 산술이라
	 * 요청마다 DB 를 타지 않고, 응답에 사용자별 값이 없어 모든 사용자에게 같다(FR-MISSION-02).
	 *
	 * <p>bbox 포함 판정은 미션 사각형이 아니라 귀속점(판정 사각형 중앙 셀 중심) 기준이다 — 마커가 찍히는
	 * 자리와 세는 기준이 같아야 화면 밖 데이터가 화면 안 마커로 세어지지 않는다(D3). 항목은 regionCode
	 * 오름차순이고 무귀속(null) 항목이 마지막이며, 범위 안에 미션이 없으면 빈 목록이다.
	 * 뒤집힌 bbox 는 INVALID_VIEWPORT, 한 변이 단위별 상한(RegionUnit.maxSpanDeg)을 넘으면 VIEWPORT_TOO_LARGE.
	 */
	List<MissionRegionAggregateResponseDto> getMissionAggregates(
		ViewportBounds bounds, MissionType type, RegionUnit unit);

	/**
	 * 미션별 내 진행도와 스탬프 보유 여부 (MSG-398). 진행도는 스탬프 판정과 같은 술어다 — 미션 기간
	 * 안에 촬영한 내 영상(status &lt;&gt; 'DELETED')이 있는 격자 수를 조회 시점에 세며 별도 집계 값을 두지
	 * 않는다(FR-MISSION-18, D8). 존재하지 않는 missionId 는 결과에서 빠지고,
	 * 기간이 끝난 미션도 조회된다. 빈 입력은 빈 결과다(예외 아님).
	 */
	List<MissionProgressResponseDto> getMyProgress(long userId, List<Long> missionIds);

	/**
	 * 미션 하나의 상세 (MSG-399). 미션 정보·렌더 shape 에 내 진행도(getMyProgress 와 같은 계산), 전역 공개
	 * 게이트를 통과한 전체 영상 수, COURSE 면 포토스팟별 방문 여부·영상 수를 담는다. 사용자별 값이라 전역
	 * 캐시에 넣지 않고, 기간 판정도 하지 않는다 — 기간이 끝난 미션도 행이 남아 있으면 조회된다.
	 * 존재하지 않는 missionId 는 MISSION_NOT_FOUND(12404) — 단일 리소스 요청이라 목록(빈 결과)과 다르다.
	 */
	MissionDetailResponseDto getMissionDetail(long missionId, long userId);
}
