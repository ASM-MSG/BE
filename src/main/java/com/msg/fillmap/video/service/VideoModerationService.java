package com.msg.fillmap.video.service;

/**
 * 운영 판단에 따른 영상 노출 차단 · 복구 (MSG-193). 신고(MSG-192)로 모인 문제 영상을 관리자가 내리고
 * 되돌리는 상태 전이만 담당한다.
 *
 * HTTP 로 노출하지 않는다 (D1) — 관리자 인증 체계가 없는 상태에서 엔드포인트를 열면 누구나 남의 영상을
 * 내릴 수 있다. 호출 주체는 후속 관리자 API(MSG-195)이고, 누가 눌렀는지의 감사 이력도 그쪽 몫이라
 * 파라미터에 관리자 식별자를 두지 않는다.
 *
 * 노출 차단 자체는 이 서비스가 하지 않는다. 재생·전역 대표·전역 목록·내 영상 리스트가 이미 전부
 * ACTIVE 로 필터하고 있어서, 상태만 바꾸면 그 순간부터 모든 경로에서 사라진다.
 */
public interface VideoModerationService {

	/** ACTIVE 영상을 BLINDED 로 전환한다. 대표였다면 남은 ACTIVE 영상으로 대표를 재선정한다. */
	void blind(long videoId);

	/** BLINDED 영상을 ACTIVE 로 해제한다. 대표는 원복하지 않는다. */
	void unblind(long videoId);
}
