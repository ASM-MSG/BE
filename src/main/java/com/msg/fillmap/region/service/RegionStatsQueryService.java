package com.msg.fillmap.region.service;

import java.util.List;
import java.util.Optional;

/**
 * 행정동별 수집률 조회 계약 (MSG-156, Owner A 제공 → RegionController(Owner A) 소비). 155 RegionStatsCommandService(write)와
 * 대칭인 read 서비스로 신설한다 — geospatial 역지오코딩인 RegionQueryService 에 얹지 않는다(§D5). 순수 SELECT(geospatial 0).
 * region 패키지 내부 서비스라 크로스오너 계약이 아니다(§계약 변경).
 */
public interface RegionStatsQueryService {

	/**
	 * 사용자의 행정동별 수집률 리스트. parentCode 가 null 이면 전국, 아니면 그 시군구 산하만(실존하지 않으면 6404 — §D3).
	 * collectedOnly=true 면 collected_count>0 행만, false 면 손댄 행 전부(롤백 0-row 포함, §D1).
	 * 수집이 없거나 필터 결과가 없으면 빈 리스트(에러 아님 — §D3).
	 */
	List<RegionStatView> findStats(long userId, String parentCode, boolean collectedOnly);

	/**
	 * 좌표 1점이 속한 행정동의 내 수집률 단건 (MSG-153 ② by-point, 도감 진입 초기값). resolveByPoint 로 행정동을 판정하고
	 * 그 행정동의 수집률을 낸다 — 수집이 없어도 0% 로 합성해 present(§D6). 좌표가 어떤 행정동에도 안 속하면(바다·국외)
	 * Optional.empty(200 + null, 93 패턴), 서비스 범위 밖 좌표면 resolveByPoint 가 INVALID_COORDINATE(6400)를 던진다.
	 */
	Optional<RegionStatView> findStatByPoint(long userId, double lat, double lon);

	/**
	 * 격자 중심점이 속한 행정동의 내 수집률 단건 (MSG-153 ③ by-grid, 격자 클릭). GridEncoder.center 로 얻은 중심점을
	 * resolveByPoint 로 판정 — 155 refreshRegionStats 와 같은 축이라 탐험률·라벨이 일치한다(§D2). 중심점이 무귀속이거나
	 * gridId 형식이 이상하면 Optional.empty(200 + null, 별도 에러 코드 없음 — §D6).
	 */
	Optional<RegionStatView> findStatByGrid(long userId, String gridId);

	/**
	 * 전국 탐험률 재료 (MSG-406, 도감·프로필 헤더의 "전체 지도 N% 탐험"). 분자는 내 region_stats.collected_count 합,
	 * 분모는 regions.total_grid_count 합이고 둘 다 반올림·clamp 없는 원값이다 — 비율과 표시 자릿수, 100 상한은
	 * 화면 몫(§D-2·D-3). 합산이라 수집이 0 이어도 분자 0 으로 항상 present 다(Optional 아님).
	 */
	RegionNationalStatView findNationalStat(long userId);
}
