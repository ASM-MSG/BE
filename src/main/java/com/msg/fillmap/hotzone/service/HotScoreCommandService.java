package com.msg.fillmap.hotzone.service;

// com.msg.fillmap.hotzone.service — Owner A 제공, Owner B(video)가 소비. 실질 크로스오너 경계.
public interface HotScoreCommandService {

	/**
	 * 업로드 확정 신호 +1 (현재 버킷 ZINCRBY + EXPIRE). Redis 실패는 구현 내부에서 삼키고
	 * warn 로깅만 한다 — 호출자에게 예외를 전파하지 않는다 (FR-6).
	 */
	void recordUpload(String gridId);
}
