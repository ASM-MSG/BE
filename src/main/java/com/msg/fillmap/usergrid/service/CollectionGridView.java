package com.msg.fillmap.usergrid.service;

import java.time.LocalDateTime;

/**
 * 갤러리 격자 항목 내부 뷰 (서비스 간 계약, MSG-153). gridY/gridX 는 GridEncoder.decode(gridId) 산출값,
 * coverThumbnailUrl 은 presign 된 GET URL(없으면 null), coverDurationSec 은 cover 영상 길이(초) —
 * READY 게이트 밖이라 인코딩 중에도 실리고 cover 자체가 없을 때만 null 이다(MSG-388).
 * regionName 은 격자 중심점 행정동 이름(무귀속이면 null,
 * MSG-167 §D4). zoneName/zoneCell 은 격자 표시명의 구역 부분(MSG-341) — 구역 밖 격자면 둘 다 null 이고
 * 그때 클라이언트가 regionName 으로 폴백한다. HTTP 응답 DTO 로의 변환은 컨트롤러가 한다
 * (CollectionSummaryView 대칭).
 */
public record CollectionGridView(
	String gridId,
	int gridY,
	int gridX,
	LocalDateTime firstCollectedAt,
	LocalDateTime lastUploadedAt,
	int videoCount,
	Long coverVideoId,
	String coverThumbnailUrl,
	Integer coverDurationSec,
	String regionName,
	String zoneName,
	String zoneCell
) {
}
