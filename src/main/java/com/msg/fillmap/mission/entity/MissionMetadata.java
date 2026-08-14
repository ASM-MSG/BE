package com.msg.fillmap.mission.entity;

/**
 * 미션 메타데이터 값 묶음 (MSG-383 §데이터 모델). 생성(빌더)과 갱신(applyMetadata) 두 자리에서 같은
 * 8개 값을 다루므로 순수 값 record 로 묶는다 — String 8개를 파라미터로 나열하면 순서를 바꿔도 컴파일이
 * 통과하는 자리다.
 *
 * {@code @Embeddable} 이 아니다: 전 필드 null 인 embedded 를 Hibernate 가 null 객체로 돌려주는 경우가
 * 있어 조회 경로에 NPE 함정이 생기는데, 이 티켓 시점의 미션 대부분이 정확히 그 상태다.
 *
 * imageUrl 은 우리 스토리지(S3) URL 만 담는다(MSG-383 §D7). 축제 시더가 MSG-384 부터 값을 넣고,
 * 팝업·코스 시더는 아직 null 이다(후속 티켓). 갱신 때 이미 채운 값을 null 로 덮지 않는 병합은 이 record 가
 * 아니라 시더 호출부에 있다 — 세 시더가 {@code applyMetadata} 를 공유해서 여기서 바꾸면 셋 다 달라진다.
 */
public record MissionMetadata(
	String description, String placeName, String sourceUrl, String operationTime, String imageUrl,
	Integer distanceMeters, Integer durationMinutes, Integer difficulty) {
}
