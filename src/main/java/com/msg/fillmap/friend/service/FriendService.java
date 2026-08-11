package com.msg.fillmap.friend.service;

import java.util.List;

import com.msg.fillmap.friend.dto.FriendCodeResponseDto;
import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.FriendPreviewResponseDto;
import com.msg.fillmap.friend.dto.FriendProfileResponseDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.RegionAggregateView;
import com.msg.fillmap.video.dto.FriendGridVideoResponseDto;

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

	/**
	 * 친구 목록 (MSG-186 FR-1~4). 방향 무관 ACCEPTED 전체, 페이징 없음. sort 는 null·"recent"(기본,
	 * 수락 시각 내림차순)·"nickname"(닉네임순)만 허용하고 그 외 값은 9420 이다. 친구 0명은 빈 리스트.
	 */
	List<FriendListItemResponseDto> getFriends(Long userId, String sort);

	/**
	 * 친구 프로필 + 도감 요약 + 최근 수집 격자 (MSG-186 FR-5~8). ACCEPTED 관계 존재가 유일한 열람 조건이며,
	 * 비친구·본인 ID·대기 중 상대·미존재 userId 는 전부 같은 9424 다 — 관계·계정 존재를 숨긴다 (§D4).
	 * 판정은 요청 시점 실시간이라 친구 삭제 직후 조회도 9424 로 수렴한다.
	 */
	FriendProfileResponseDto getFriendProfile(Long userId, Long targetUserId);

	/**
	 * 친구 격자 뷰포트 조회 (MSG-187 FR-1·3·8·10). ACCEPTED 관계 존재가 열람 조건이라 실패는 프로필과 같은
	 * 9424 하나다(비친구·본인 ID·대기 중 상대·미존재 userId — 관계·계정 존재 은닉). 판정 통과 후에는 내
	 * 뷰포트 조회(MSG-90)와 같은 계약을 그대로 재사용하므로 뷰포트·커서·size 검증 규칙과 에러가 동일하다.
	 * 친구가 그 뷰포트에 점령한 격자가 없으면 빈 페이지다(예외 아님).
	 */
	OccupiedGridPage getFriendGrids(Long userId, Long targetUserId, ViewportBounds bounds, String cursor, int size);

	/**
	 * 친구 격자 집계 조회 (MSG-356 FR-4) — 축소한 시야에서 친구의 점령 격자를 행정 단위로 묶어 센 목록이다.
	 * 열람 조건도 실패도 뷰포트 조회와 같은 9424 하나이고(비친구·본인 ID·대기 중 상대·미존재 userId),
	 * 판정을 통과한 뒤에는 내 집계 조회와 같은 계약에 그대로 위임하므로 뷰포트·단위 검증 규칙과 에러가 동일하다.
	 * 친구가 그 뷰포트에 점령한 격자가 없으면 빈 목록이다(예외 아님).
	 */
	List<RegionAggregateView> getFriendGridAggregates(Long userId, Long targetUserId, ViewportBounds bounds,
		RegionUnit unit);

	/**
	 * 친구 격자 영상 목록 (MSG-187 FR-4·5·9). 그 친구가 그 격자에 올린 영상 중 친구에게 허용된
	 * 공개범위(PUBLIC·FRIENDS)의 ACTIVE·READY 영상만 최신순으로 준다 — PRIVATE 은 제외다. 실패는 9424
	 * 하나이고, 친구가 점령하지 않은 격자·존재하지 않는 gridId 는 빈 리스트다(예외 아님).
	 */
	List<FriendGridVideoResponseDto> getFriendGridVideos(Long userId, Long targetUserId, String gridId);

	/** 요청 수락 (FR-10·13). 조회 키 (requesterId, 나)가 수신자 본인 검증을 구조적으로 강제한다. */
	void accept(Long userId, Long requesterId);

	/** 요청 거절 (FR-11·13). 행 DELETE(§D3) — 통지 없음, 상대는 재요청 가능. */
	void reject(Long userId, Long requesterId);

	/** 친구 삭제 (FR-12·13). 방향 무관 ACCEPTED 행 DELETE — 대기 중 요청은 대상이 아니다(9424). */
	void deleteFriend(Long userId, Long friendUserId);
}
