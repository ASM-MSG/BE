package com.msg.fillmap.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.notification.dto.NotificationPageResponseDto;
import com.msg.fillmap.notification.dto.NotificationUnreadCountResponseDto;
import com.msg.fillmap.notification.service.NotificationInboxService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 알림함 API (MSG-434). 3-layer 얇게 — principal userId + 서비스 호출 + SuccessResponse 변환만.
 * 프로필 페이지의 알림함 카드, 프로필 카드의 "새 알림 N개" 문구, 지도 홈 프로필 이미지의 빨간 점이
 * 이 4개를 소비한다. 발송 내부 상태와 내부 키는 어떤 응답에도 싣지 않는다.
 */
@Tag(name = "알림 (Notification)", description = "받은 알림 목록 조회와 읽음 처리 API.")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationInboxController {

	private final NotificationInboxService notificationInboxService;

	@Operation(
		summary = "알림 목록 조회",
		description = "받은 알림을 최신순으로 한 페이지 반환한다. 최근 30일 이내 생성분만 보이고, "
			+ "알림 설정을 꺼서 발송되지 않은 알림은 빠진다 — 전송률 상한이나 푸시 토큰 없음으로 발송되지 "
			+ "않은 알림은 보인다. 다음 페이지는 응답의 nextCursor 를 cursor 로 다시 넘긴다. "
			+ "목록 조회는 읽음 상태를 바꾸지 않는다."
	)
	@GetMapping
	public SuccessResponse<NotificationPageResponseDto> getInbox(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "직전 응답의 nextCursor — 생략하면 첫 페이지", example = "123")
		@RequestParam(required = false) Long cursor,
		@Parameter(description = "페이지 크기 — 0 이하면 20, 50 초과면 50 으로 자른다", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		return SuccessResponse.of(notificationInboxService.getInbox(principal.userId(), cursor, size));
	}

	@Operation(
		summary = "안읽은 알림 개수 조회",
		description = "목록과 같은 노출 조건으로 안읽은 알림 수를 센다 — 없으면 0 이다."
	)
	@GetMapping("/unread-count")
	public SuccessResponse<NotificationUnreadCountResponseDto> getUnreadCount(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(notificationInboxService.getUnreadCount(principal.userId()));
	}

	@Operation(
		summary = "알림 하나 읽음 처리",
		description = "행을 탭했을 때 그 알림을 읽음으로 바꾼다 — 이미 읽은 알림을 다시 요청해도 성공이고 "
			+ "최초로 읽은 시각이 그대로 남는다. 없는 알림이나 남의 알림이면 10404 로, 둘을 구분하지 않는다."
	)
	@PatchMapping("/{notificationId}/read")
	public SuccessResponse<Void> markRead(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "읽음 처리할 알림 ID", example = "123") @PathVariable long notificationId
	) {
		notificationInboxService.markRead(principal.userId(), notificationId);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "알림 모두 읽음 처리",
		description = "안읽은 알림을 전부 읽음으로 바꾼다 — 안읽은 알림이 하나도 없어도 성공한다."
	)
	@PatchMapping("/read-all")
	public SuccessResponse<Void> markAllRead(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		notificationInboxService.markAllRead(principal.userId());
		return new SuccessResponse<>(null);
	}
}
