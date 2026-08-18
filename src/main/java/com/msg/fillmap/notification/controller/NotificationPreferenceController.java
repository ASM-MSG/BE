package com.msg.fillmap.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.notification.dto.NotificationPreferenceResponseDto;
import com.msg.fillmap.notification.dto.NotificationPreferenceUpdateRequestDto;
import com.msg.fillmap.notification.service.NotificationPreferenceService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 알림 설정 API (MSG-180). 3-layer 얇게 — principal userId + 서비스 호출 + SuccessResponse 변환만.
 * "설정 > 알림 설정" 화면 백엔드 — off 카테고리는 발송만 막고 outbox 기록은 SKIPPED 로 남는다 (FR-8).
 */
@Tag(name = "알림 (Notification)", description = "카테고리별 알림 수신 설정 조회/토글 API.")
@RestController
@RequestMapping("/api/notifications/preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

	private final NotificationPreferenceService notificationPreferenceService;

	@Operation(
		summary = "알림 설정 조회",
		description = "카테고리 7종(BADGE·HOTZONE·REMIND·VIDEO·WEEKLY·FRIEND·MISSION_NEARBY) 전부의 수신 상태를 반환한다. "
			+ "설정을 만진 적 없는 사용자는 전부 true 다 — opt-out 기본 전부 on. "
			+ "MODERATION 은 설정 대상이 아니라 목록에 없다 (수신 거부 불가)."
	)
	@GetMapping
	public SuccessResponse<NotificationPreferenceResponseDto> getPreferences(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(notificationPreferenceService.getPreferences(principal.userId()));
	}

	@Operation(
		summary = "카테고리 수신 토글",
		description = "카테고리 하나의 수신 여부를 바꾸고 변경 후 전체 상태를 반환한다 — 같은 값 재전환은 멱등. "
			+ "category 가 7종(BADGE·HOTZONE·REMIND·VIDEO·WEEKLY·FRIEND·MISSION_NEARBY, 대소문자 무시) 외면 10420 이다. "
			+ "off 는 발송만 막고 off 중 쌓인 알림이 on 복귀 후 재발송되는 일은 없다."
	)
	@PatchMapping("/{category}")
	public SuccessResponse<NotificationPreferenceResponseDto> update(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "알림 카테고리 — BADGE·HOTZONE·REMIND·VIDEO·WEEKLY·FRIEND·MISSION_NEARBY (대소문자 무시)",
			example = "HOTZONE")
		@PathVariable String category,
		@Valid @RequestBody NotificationPreferenceUpdateRequestDto request
	) {
		return SuccessResponse.of(
			notificationPreferenceService.update(principal.userId(), category, request.enabled()));
	}
}
