package com.msg.fillmap.mission.entity;

/**
 * 미션 메타데이터 값 묶음 (MSG-383 §데이터 모델). 생성(빌더)과 갱신(applyMetadata) 두 자리에서 같은
 * 8개 값을 다루므로 순수 값 record 로 묶는다 — String 8개를 파라미터로 나열하면 순서를 바꿔도 컴파일이
 * 통과하는 자리다.
 *
 * {@code @Embeddable} 이 아니다: 전 필드 null 인 embedded 를 Hibernate 가 null 객체로 돌려주는 경우가
 * 있어 조회 경로에 NPE 함정이 생기는데, 이 티켓 시점의 미션 대부분이 정확히 그 상태다.
 *
 * imageUrl 은 우리 스토리지(S3) URL 만 담는다(§D7). 이 티켓의 시더 3종은 전부 null 을 넣고, 수집·미러링은
 * MSG-384 가 맡는다 — 그때 시더 재실행이 이미 채운 값을 null 로 덮지 않게 하는 처리가 함께 필요하다.
 */
public record MissionMetadata(
	String description, String placeName, String sourceUrl, String operationTime, String imageUrl,
	Integer distanceMeters, Integer durationMinutes, Integer difficulty) {
}
