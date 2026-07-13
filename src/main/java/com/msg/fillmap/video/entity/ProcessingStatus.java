package com.msg.fillmap.video.entity;

/**
 * 영상 처리 파이프라인 상태 (videos.processing_status). DDL CHECK 와 일치.
 * BLURRING(AI Highlight-Blur)은 별도 에픽, UPLOADED→ENCODING→READY 가 MVP 기본 흐름.
 */
public enum ProcessingStatus {
	UPLOADED,
	ENCODING,
	BLURRING,
	READY,
	FAILED
}
