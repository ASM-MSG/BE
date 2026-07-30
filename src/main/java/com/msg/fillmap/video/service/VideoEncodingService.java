package com.msg.fillmap.video.service;

public interface VideoEncodingService {

	/**
	 * 원본을 720p H.264 로 변환하고 썸네일을 추출해 READY 로 전이시킨다 (비동기).
	 * 실패해도 예외를 밖으로 던지지 않고 FAILED 로 기록한다 — 호출자는 결과를 기다리지 않는다.
	 *
	 * originalKey 는 트리거 시점에 고정한 이 시도의 원본 S3 key 다 (MSG-241). 태스크의 모든 상태 쓰기·
	 * 다운로드가 이 키 기준으로 돌아, 교체 후 도착한 옛 태스크의 결과가 새 파일 상태를 덮지 못한다.
	 */
	void encode(Long videoId, String originalKey);
}
