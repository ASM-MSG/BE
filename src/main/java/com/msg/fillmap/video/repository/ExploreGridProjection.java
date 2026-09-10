package com.msg.fillmap.video.repository;

/**
 * 전역 탐색 격자 카드 프로젝션 (단일 네이티브 쿼리 결과, MSG-238 §도메인 1). 그 행정동(grids.region_code)
 * 격자들 중 전역 노출 게이트(ACTIVE·PUBLIC·READY) 통과 영상이 1건 이상인 격자만 잡힌다(§D1). 컬럼 별칭을
 * getter 프로퍼티명에 맞춘다. coverThumbnailKey 는 findGlobalCover(87) 3키 규칙로 뽑은 커버의 S3 key,
 * presign 은 서비스가 한다(§D7). gridY/gridX 는 grids 컬럼 직접 — 이미 조인하므로 GridEncoder 불요.
 */
public interface ExploreGridProjection {

	String getGridId();

	Long getGridY();

	Long getGridX();

	/** 그 격자의 게이트 통과 영상 수 — 카드 "N개 영상"(§D1, 헤더 videoCount 의 합산 성분). */
	Integer getVideoCount();

	String getCoverThumbnailKey();

	Short getCoverDurationSec();
}
