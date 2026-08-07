package com.msg.fillmap.usergrid.service;

import java.time.LocalDateTime;

/**
 * 동 단위 내 영상 항목 내부 뷰 (서비스 간 계약, MSG-167 §D3). thumbnailUrl 은 presign 된 GET URL
 * (READY 이전이면 null), gridId 는 항목별 격자 라벨용(Open Q2 — usergrid 전용 DTO 분화).
 * zoneName/zoneCell 은 그 격자 라벨의 구역 부분(MSG-341, 구역 밖이면 둘 다 null) — 이 화면은 행정동 헤더
 * 아래 목록이라 폴백 이름을 문맥에서 이미 알아 항목에 regionName 을 따로 담지 않는다. HTTP 응답
 * DTO 로의 변환은 컨트롤러가 한다 (CollectionGridView 대칭).
 */
public record RegionVideoView(
	Long videoId,
	String gridId,
	String thumbnailUrl,
	String processingStatus,
	Integer durationSec,
	LocalDateTime createdAt,
	String zoneName,
	String zoneCell
) {
}
