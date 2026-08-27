package com.msg.fillmap.video.service;

public interface VideoEncodingService {

	/**
	 * 영속 작업을 현재 스레드에서 처리한다.
	 * 실행 위치와 재시도는 EncodingJobPoller가 관리한다.
	 */
	void encode(EncodingJobClaim claim);
}
