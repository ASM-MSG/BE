package com.msg.fillmap.friend.service;

import java.util.List;

import com.msg.fillmap.friend.dto.FriendCodeResponseDto;
import com.msg.fillmap.friend.dto.FriendPreviewResponseDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;

public interface FriendService {

	/** 내 친구 코드 조회 (FR-1). 코드 공유는 사용자가 임의 채널(카톡 등)로 — 서버는 값만 준다. */
	FriendCodeResponseDto getMyFriendCode(Long userId);

	/**
	 * 코드 미리보기 (FR-3) — 요청 확정 전 확인 화면용 advisory 조회. 자기 코드·이미 친구인 상대도
	 * 성공한다(단순 조회). 요청 검증은 {@link #request}가 전부 재수행한다.
	 */
	FriendPreviewResponseDto preview(String friendCode);

	/**
	 * 친구 요청 (FR-4~8). 행 없음 → PENDING 생성, 역방향 대기 → 기존 행 ACCEPTED 승격(자동 수락).
	 * 응답 status 가 두 경우를 구분한다 — FE 의 "요청 보냄" vs "친구가 됐어요" 유일 신호.
	 */
	FriendRequestCreateResponseDto request(Long userId, String friendCode);

	/** 받은 대기 요청 목록 (FR-9). 최신 요청 우선, 페이징 없음(소량 천장 — 필요 시 후속). */
	List<ReceivedFriendRequestResponseDto> getReceivedRequests(Long userId);

	/** 요청 수락 (FR-10·13). 조회 키 (requesterId, 나)가 수신자 본인 검증을 구조적으로 강제한다. */
	void accept(Long userId, Long requesterId);

	/** 요청 거절 (FR-11·13). 행 DELETE(§D3) — 통지 없음, 상대는 재요청 가능. */
	void reject(Long userId, Long requesterId);

	/** 친구 삭제 (FR-12·13). 방향 무관 ACCEPTED 행 DELETE — 대기 중 요청은 대상이 아니다(9424). */
	void deleteFriend(Long userId, Long friendUserId);
}
