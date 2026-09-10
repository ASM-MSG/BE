package com.msg.fillmap.usergrid.service;

import java.time.LocalDateTime;

/**
 * 친구에게 보여줄 격자 항목 내부 뷰 (서비스 간 계약, MSG-186 D6). CollectionGridView 에서 cover 2필드를 뺀
 * 형상이다 — 친구 화면은 표시 전용이라 영상 ID 를 노출하지 않고, thumbnailUrl 은 cover 가 아니라 "재생 허용
 * 영상 중 1건"의 presign 된 GET URL(그런 영상이 없으면 null)이다. zoneName/zoneCell 은 격자 표시명의 구역
 * 부분(MSG-341) — 구역 밖 격자면 둘 다 null 이고 그때 클라이언트가 regionName 으로 폴백한다. 이름은 격자
 * 좌표에서 나오는 공개 정보라 친구 화면이라고 가려야 할 축이 아니다. HTTP 응답 DTO 변환은 소비처가 한다.
 */
public record FriendCollectionGridView(
	String gridId,
	int gridY,
	int gridX,
	LocalDateTime firstCollectedAt,
	LocalDateTime lastUploadedAt,
	int videoCount,
	String thumbnailUrl,
	String regionName,
	String zoneName,
	String zoneCell
) {
}
