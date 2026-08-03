package com.msg.fillmap.video.entity;

/**
 * 영상 공개 범위 (videos.visibility). DDL CHECK 와 일치. 업로드 시 미지정이면 PUBLIC 이다
 * (MSG-204 — 미지정 기본 PUBLIC 확정).
 */
public enum Visibility {
	PUBLIC,
	PRIVATE,
	/**
	 * 친구 공개 (MSG-285) — 소유자 본인과 ACCEPTED 친구(방향 무관)만 재생할 수 있다. 비친구에겐
	 * PRIVATE 비소유자와 동일한 403. 전역 노출 쿼리는 PUBLIC 등식이라 FRIENDS 행을 제외한다.
	 */
	FRIENDS
}
