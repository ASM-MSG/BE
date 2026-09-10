package com.msg.fillmap.mission.entity;

/**
 * 미션 메타데이터 값 묶음 (MSG-383 §데이터 모델). 생성(빌더)과 갱신(applyMetadata) 두 자리에서 같은
 * 8개 값을 다루므로 순수 값 record 로 묶는다 — String 8개를 파라미터로 나열하면 순서를 바꿔도 컴파일이
 * 통과하는 자리다.
 *
 * {@code @Embeddable} 이 아니다: 전 필드 null 인 embedded 를 Hibernate 가 null 객체로 돌려주는 경우가
 * 있어 조회 경로에 NPE 함정이 생기는데, 이 티켓 시점의 미션 대부분이 정확히 그 상태다.
 *
 * imageUrl 은 우리 스토리지(S3) URL 만 담는다(MSG-383 §D7). 축제 시더가 MSG-384 부터, 팝업·코스 시더가
 * MSG-394 부터 값을 넣는다. 갱신 때 이미 채운 값을 null 로 덮지 않는 병합은 {@code withImageFallback}
 * 이지만, 그것을 <b>부를지 말지는 시더 호출부가 고른다</b> — 세 시더가 {@code applyMetadata} 를 공유해서
 * 그쪽 의미를 바꾸면 셋의 갱신 동작이 한꺼번에 달라진다.
 */
public record MissionMetadata(
	String description, String placeName, String sourceUrl, String operationTime, String imageUrl,
	Integer distanceMeters, Integer durationMinutes, Integer difficulty) {

	/**
	 * 새 스냅샷에 이미지가 없으면 이미 채운 대표 이미지를 그대로 둔다 (MSG-384 D2 · MSG-394 D4).
	 * {@code Mission.applyMetadata} 가 8개 필드를 통째로 대입하는 부분 갱신 아닌 메서드라, 원본이 내려가
	 * imageKey 가 빈 스냅샷이 오면 우리 버킷에 사본이 멀쩡히 있는데 그 주소를 아는 곳이 없어진다 —
	 * 원본이 사라진 바로 그 상황에서 사본까지 잃는 셈이다. 팝업은 기간이 끝나면 상세 페이지 자체가
	 * 사라져 축제보다 이 함정에 더 잘 빠진다.
	 *
	 * <b>갱신 경로의 이미지 한 필드만의 예외다.</b> 소개문·장소·원문 링크는 외부 원본을 비추는 값이라
	 * 새 스냅샷으로 덮는 것이 맞고, 신규 INSERT 는 이미지가 없으면 NULL 그대로다. 대가로 시더가 이미지를
	 * 지우는 수단을 잃는다 — 잘못 올라간 사진의 내리기는 억제 목록과 수동 SQL 이 맡는다(MSG-394 D7).
	 */
	public MissionMetadata withImageFallback(String existingImageUrl) {
		if (imageUrl != null) {
			return this;
		}
		return new MissionMetadata(description, placeName, sourceUrl, operationTime, existingImageUrl,
			distanceMeters, durationMinutes, difficulty);
	}
}
