package com.msg.fillmap.video.service;

import java.util.List;

import com.msg.fillmap.video.dto.ExploreSort;
import com.msg.fillmap.video.dto.RegionExploreResponseDto;
import com.msg.fillmap.video.dto.RegionGridCountResponseDto;

/**
 * 전역 탐색 (MSG-238) — 행정동 축으로 전역 공개 콘텐츠(타인 영상 포함)를 읽는다. video 패키지 호스팅(§D5):
 * 게이트·커버 규칙·presigner 가 전부 videos 자산이라 여기 모인다. 크로스오너 계약 아님 — A→B Java 호출이
 * 없어 GridQueryService 확장도 신규 계약도 두지 않는다(§D5 기각). 전 구간 read 전용.
 */
public interface RegionExploreService {

	/**
	 * 행정동 격자 카드 리스트 + 헤더 카운트 (§D2 — 패널은 limit=3, 전체 보기는 limit 생략 + sort 칩).
	 * limit null = 전부, 1 미만은 1 로 보정. 미존재/무콘텐츠 regionCode 는 regionName null(실존 시 이름)·
	 * 카운트 0·빈 배열 — 예외가 아니다.
	 */
	RegionExploreResponseDto getRegionGrids(String regionCode, ExploreSort sort, Integer limit);

	/** 전체 지역 리스트 — 게이트 통과 격자 ≥1 행정동만, gridCount DESC·regionCode ASC (§D3). */
	List<RegionGridCountResponseDto> getExploreRegions();
}
