package com.msg.fillmap.usergrid.repository;

import java.time.LocalDateTime;

/**
 * 갤러리 격자 목록 프로젝션 (단일 네이티브 쿼리 결과, MSG-153). user_grids 와 cover 영상(videos LEFT JOIN)을
 * 읽고, regionName 은 grids·regions LEFT JOIN(PK/equi — geospatial 없음, MSG-167 §D4)으로 붙인다.
 * 컬럼 별칭을 getter 프로퍼티명에 맞춘다. gridY/gridX 는 여기 없다 — 서비스가 GridEncoder.decode(gridId) 로
 * 산출한다(D5). coverThumbnailKey 는 S3 key, presign 은 서비스가 한다.
 */
public interface CollectionGridProjection {

	String getGridId();

	LocalDateTime getFirstCollectedAt();

	LocalDateTime getLastUploadedAt();

	Integer getVideoCount();

	Long getCoverVideoId();

	String getCoverThumbnailKey();

	/** 격자 중심점 행정동 이름(grids.region_code 경유). 무귀속/미판정이면 null (MSG-167 §D4). */
	String getRegionName();
}
