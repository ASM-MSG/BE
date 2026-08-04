package com.msg.fillmap.friend.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.friend.dto.FriendCodeResponseDto;
import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.FriendPreviewResponseDto;
import com.msg.fillmap.friend.dto.FriendProfileResponseDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateRequestDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.friend.service.FriendService;
import com.msg.fillmap.response.SuccessResponse;

@Tag(
	name = "친구 (Friend)",
	description = "고정 친구 코드 기반 친구 관계 API — 코드·요청·수락·거절·삭제 (MSG-185)와 "
		+ "친구 목록·친구 프로필 조회 (MSG-186). 인증 필수."
)
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

	private final FriendService friendService;

	@Operation(
		summary = "내 친구 코드 조회",
		description = "가입 시 자동 부여된 고정 8자 코드를 반환한다. 상대에게 임의 채널(카톡 등)로 공유하면 "
			+ "상대가 이 코드로 친구 요청을 보낼 수 있다. 재발급 없음."
	)
	@GetMapping("/code")
	public SuccessResponse<FriendCodeResponseDto> getMyFriendCode(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(friendService.getMyFriendCode(principal.userId()));
	}

	@Operation(
		summary = "친구 코드 미리보기",
		description = "요청을 보내기 전 확인 화면용 — 코드 소유자의 닉네임만 반환한다. "
			+ "요청 가능 여부 검증(자기 자신·중복 등)은 요청 API 가 수행한다."
	)
	@GetMapping("/preview")
	public SuccessResponse<FriendPreviewResponseDto> preview(@RequestParam("code") String code) {
		return SuccessResponse.of(friendService.preview(code));
	}

	@Operation(
		summary = "친구 요청",
		description = "상대의 친구 코드로 요청을 보낸다. 응답 status 가 PENDING 이면 상대 수락 대기, "
			+ "ACCEPTED 면 상대가 먼저 보낸 요청이 있어 즉시 친구 성립(자동 수락)이다."
	)
	@PostMapping("/requests")
	public SuccessResponse<FriendRequestCreateResponseDto> request(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody FriendRequestCreateRequestDto request
	) {
		return SuccessResponse.of(friendService.request(principal.userId(), request.friendCode()));
	}

	@Operation(
		summary = "친구 목록 조회",
		description = "수락된 친구 전체를 반환한다 — 누가 먼저 요청했는지와 무관하다. 기본 정렬은 친구가 된 "
			+ "시각 내림차순이고 sort=nickname 이면 닉네임순이다. 친구가 없으면 빈 배열."
	)
	@GetMapping
	public SuccessResponse<List<FriendListItemResponseDto>> getFriends(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "정렬 기준 — recent(기본, 친구가 된 시각 내림차순) 또는 nickname", example = "nickname")
		@RequestParam(required = false) String sort
	) {
		return SuccessResponse.of(friendService.getFriends(principal.userId(), sort));
	}

	@Operation(
		summary = "친구 프로필·도감 요약 조회",
		description = "친구의 프로필(닉네임·프로필 이미지·도감 색상)과 도감 요약(수집 격자 수·영상 총합·"
			+ "방문 동 수), 최근 수집 격자 최대 30개를 한 번에 반환한다. 도감 요약 수치는 그 친구가 자기 "
			+ "도감에서 보는 값과 같다. 썸네일은 그 격자에 재생 가능한 공개 영상이 있을 때만 붙는다. "
			+ "친구가 아닌 사용자·본인·존재하지 않는 사용자 조회는 모두 같은 404 다."
	)
	@GetMapping("/{userId}/profile")
	public SuccessResponse<FriendProfileResponseDto> getFriendProfile(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long userId
	) {
		return SuccessResponse.of(friendService.getFriendProfile(principal.userId(), userId));
	}

	@Operation(
		summary = "받은 친구 요청 목록",
		description = "내가 수신자인 대기 중 요청을 최신순으로 반환한다. 항목의 requesterId 를 "
			+ "수락/거절 경로 변수로 그대로 쓴다."
	)
	@GetMapping("/requests/received")
	public SuccessResponse<List<ReceivedFriendRequestResponseDto>> getReceivedRequests(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(friendService.getReceivedRequests(principal.userId()));
	}

	@Operation(
		summary = "친구 요청 수락",
		description = "받은 요청을 수락해 친구 관계를 성립시킨다. 요청의 수신자 본인만 가능하다."
	)
	@PostMapping("/requests/{requesterId}/accept")
	public SuccessResponse<Void> accept(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long requesterId
	) {
		friendService.accept(principal.userId(), requesterId);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "친구 요청 거절",
		description = "받은 요청을 거절한다. 보낸 쪽에 통지는 없고, 상대는 다시 요청할 수 있다."
	)
	@PostMapping("/requests/{requesterId}/reject")
	public SuccessResponse<Void> reject(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long requesterId
	) {
		friendService.reject(principal.userId(), requesterId);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "친구 삭제",
		description = "친구 관계를 해소한다. 어느 쪽이든 삭제할 수 있고 즉시 양쪽 모두에서 사라진다. "
			+ "대기 중 요청은 대상이 아니다."
	)
	@DeleteMapping("/{userId}")
	public SuccessResponse<Void> deleteFriend(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long userId
	) {
		friendService.deleteFriend(principal.userId(), userId);
		return new SuccessResponse<>(null);
	}
}
