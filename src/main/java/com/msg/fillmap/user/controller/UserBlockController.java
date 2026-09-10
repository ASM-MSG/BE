package com.msg.fillmap.user.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.dto.BlockedUserResponseDto;
import com.msg.fillmap.user.service.UserBlockService;

/**
 * 사용자 차단 3경로 (MSG-569). 경로 변수는 {userId:[0-9]+} 로 숫자를 못박는다 — /api/users/me/... 형제
 * 경로가 있어 제약이 없으면 "me" 가 userId 자리에 잡혀 형 변환 400 으로 떨어진다. 인가는 SecurityConfig
 * 무수정 — 세 경로 모두 catch-all hasAnyRole(USER, ADMIN) 로 떨어져 로그인 필수다.
 */
@Tag(name = "사용자 차단 (User Block)",
	description = "다른 사용자를 차단·해제하고 내가 차단한 목록을 본다. 차단 관계에서는 서로의 영상·댓글이 보이지 않는다.")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserBlockController {

	private final UserBlockService userBlockService;

	@Operation(
		summary = "사용자 차단",
		description = "경로의 사용자를 차단한다. 차단하면 두 사람 사이의 친구 관계(수락됨·대기 중, 방향 무관)가 함께 "
			+ "삭제되고, 이후 서로의 영상과 댓글이 목록·재생·상세에서 보이지 않는다. 이미 차단한 사용자를 다시 "
			+ "차단해도 성공하며 최초 차단 시각이 유지된다(멱등). 자기 자신은 400 + 1430, 존재하지 않는 사용자는 "
			+ "404 + 1404 다. 신고는 차단과 무관하게 계속 할 수 있다."
	)
	@PostMapping("/{userId:[0-9]+}/block")
	public SuccessResponse<Void> block(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "차단할 사용자 ID", example = "42") @PathVariable Long userId
	) {
		userBlockService.block(principal.userId(), userId);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "사용자 차단 해제",
		description = "내가 걸은 차단을 푼다. 차단한 적 없는 사용자나 존재하지 않는 userId 도 200 이다(멱등). "
			+ "차단으로 삭제된 친구 관계는 되살아나지 않는다. 상대가 나를 차단한 행은 그대로라 그 경우 서로의 "
			+ "콘텐츠는 계속 보이지 않는다."
	)
	@DeleteMapping("/{userId:[0-9]+}/block")
	public SuccessResponse<Void> unblock(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "차단을 해제할 사용자 ID", example = "42") @PathVariable Long userId
	) {
		userBlockService.unblock(principal.userId(), userId);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "내가 차단한 사용자 목록",
		description = "내가 차단한 사용자 전부를 차단 시각 내림차순으로 페이지 없이 반환한다. 닉네임·프로필 이미지는 "
			+ "조회 시점 값이다. 나를 차단한 사용자는 포함되지 않고, 차단이 없으면 빈 배열이다."
	)
	@GetMapping("/me/blocks")
	public SuccessResponse<List<BlockedUserResponseDto>> getBlockedUsers(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(userBlockService.getBlockedUsers(principal.userId()));
	}
}
