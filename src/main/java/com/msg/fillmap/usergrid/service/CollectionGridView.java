package com.msg.fillmap.usergrid.service;

import java.time.LocalDateTime;

/**
 * 갤러리 격자 항목 내부 뷰 (서비스 간 계약, MSG-153). gridY/gridX 는 GridEncoder.decode(gridId) 산출값,
 * coverThumbnailUrl 은 presign 된 GET URL(없으면 null), regionName 은 격자 중심점 행정동 이름(무귀속이면 null,
 * MSG-167 §D4). HTTP 응답 DTO 로의 변환은 컨트롤러가 한다 (CollectionSummaryView 대칭).
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
	String regionName
) {
}
