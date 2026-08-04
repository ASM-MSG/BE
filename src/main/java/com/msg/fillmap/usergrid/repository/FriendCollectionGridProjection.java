package com.msg.fillmap.usergrid.repository;

import java.time.LocalDateTime;

/**
 * 친구에게 보여줄 격자 목록 프로젝션 (단일 네이티브 쿼리 결과, MSG-186 D6). CollectionGridProjection 과
 * 같되 cover 2필드(coverVideoId·coverThumbnailKey) 대신 thumbnailKey 하나만 둔다 — 친구 응답은 영상 ID 를
 * 노출하지 않고(비공개 영상 존재 누설 방지), 썸네일도 cover 가 아니라 "재생 허용 영상 중 1건"이라 이름이 다르다.
 * gridY/gridX 는 여기 없다 — 서비스가 GridEncoder.decode(gridId) 로 산출한다. presign 도 서비스가 한다.
 */
public interface FriendCollectionGridProjection {

	String getGridId();

	LocalDateTime getFirstCollectedAt();

	LocalDateTime getLastUploadedAt();

	Integer getVideoCount();

	/** 재생 허용 영상의 썸네일 S3 key — 그런 영상이 없으면 null(격자 사실만 내려간다, FR-6). */
	String getThumbnailKey();

	/** 격자 중심점 행정동 이름(grids.region_code 경유). 무귀속/미판정이면 null. */
	String getRegionName();
}
