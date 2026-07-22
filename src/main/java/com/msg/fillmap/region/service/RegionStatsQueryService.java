package com.msg.fillmap.region.service;

import java.util.List;

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
}
