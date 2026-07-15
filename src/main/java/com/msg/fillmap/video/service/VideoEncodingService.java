package com.msg.fillmap.video.service;

public interface VideoEncodingService {

	/**
	 * 원본을 720p H.264 로 변환하고 썸네일을 추출해 READY 로 전이시킨다 (비동기).
	 * 실패해도 예외를 밖으로 던지지 않고 FAILED 로 기록한다 — 호출자는 결과를 기다리지 않는다.
	 */
	void encode(Long videoId);
}
