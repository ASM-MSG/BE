package com.msg.fillmap.video.entity;

/**
 * 영상 라이프사이클 상태 (videos.status). DDL CHECK 와 일치.
 * DELETED 는 soft delete(MSG-72), BLINDED 는 신고 처리용.
 */
public enum VideoStatus {
	ACTIVE,
	BLINDED,
	DELETED
}
