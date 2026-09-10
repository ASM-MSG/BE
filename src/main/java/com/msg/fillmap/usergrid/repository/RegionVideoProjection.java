package com.msg.fillmap.usergrid.repository;

import java.time.LocalDateTime;

/**
 * 동 단위 내 영상 프로젝션 (단일 네이티브 쿼리 결과, MSG-167 §D3 · Open Q2). videos⨝grids 를 격자 축
 * (grids.region_code)으로 필터해 그 행정동 격자들에 올린 내 영상을 읽는다. 컬럼 별칭을 getter 프로퍼티명에
 * 맞춘다. thumbnailKey 는 S3 key, presign 은 서비스가 한다(READY 이전이면 null → thumbnailUrl null).
 * gridId 는 항목별 격자 라벨(디자인 "지역별 갤러리"의 "서면 A-14 #4")에 FE 가 쓴다 — 127 격자 단위 조회와 달리
 * 동 단위는 여러 격자에 걸쳐 항목마다 소속 격자를 안다(usergrid 전용 DTO 로 분화, §D3 Open Q2).
 */
public interface RegionVideoProjection {

	Long getVideoId();

	String getGridId();

	String getThumbnailKey();

	String getProcessingStatus();

	Integer getDurationSec();

	LocalDateTime getCreatedAt();
}
