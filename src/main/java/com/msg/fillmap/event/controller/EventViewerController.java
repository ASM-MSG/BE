package com.msg.fillmap.event.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.event.dto.EventViewerCountResponseDto;
import com.msg.fillmap.event.service.EventViewerService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.ErrorCode;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 행사방 열람 인원 API (MSG-443). 두 경로 모두 비로그인 허용(SecurityConfig permitAll, D4) —
 * 로그인이면 principal 의 userId 로 {@code u:{userId}}, 비로그인이면 X-Viewer-Session 헤더로
 * {@code s:{sessionId}} 를 세션 식별자로 쓴다. occurrenceId 존재 검증은 MSG-438 이후 후속.
 */
@Tag(name = "이벤트 (Event)", description = "이벤트 열람 인원 heartbeat·조회 API.")
@RestController
@RequestMapping("/api/event-occurrences/{occurrenceId}")
@RequiredArgsConstructor
public class EventViewerController {

	private static final String VIEWER_SESSION_HEADER = "X-Viewer-Session";
	/** 익명 세션 ID 길이 상한 — member 크기 증폭 방지 (D4, UUID 권장이나 형식 강제는 문자수뿐). */
	private static final int MAX_SESSION_ID_LENGTH = 64;

	private final EventViewerService eventViewerService;

	@Operation(
		summary = "열람 heartbeat",
		description = "이벤트를 보는 동안 30초 주기로 보낸다. 마지막 신호가 90초 이내인 세션만 열람 인원에 "
			+ "센다. 비로그인은 X-Viewer-Session 헤더(공백 아님·최대 64자) 필수 — 없으면 400. "
			+ "캐시 장애는 삼켜져 200 이다."
	)
	@PostMapping("/heartbeat")
	public SuccessResponse<Void> heartbeat(
		@PathVariable Long occurrenceId,
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@RequestHeader(value = VIEWER_SESSION_HEADER, required = false) String viewerSession
	) {
		eventViewerService.recordHeartbeat(occurrenceId, resolveViewerKey(principal, viewerSession));
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "현재 열람 인원 조회",
		description = "viewerCount 0 은 아무도 없음(표시), null 은 캐시 장애(숨김)다. 응답이 사용자 무관이라 "
			+ "인증 없이 호출할 수 있다."
	)
	@GetMapping("/viewer-count")
	public SuccessResponse<EventViewerCountResponseDto> getViewerCount(@PathVariable Long occurrenceId) {
		return SuccessResponse.of(new EventViewerCountResponseDto(eventViewerService.getViewerCount(occurrenceId)));
	}

	/**
	 * principal 우선 (D4) — 헤더가 함께 와도 무시해야 같은 사용자의 중복 탭이 항상 1명으로 수렴한다
	 * (탭마다 헤더 세션 ID 가 다를 수 있다). 접두사 u:/s: 는 userId 숫자와 익명 ID 의 충돌 방지.
	 */
	private String resolveViewerKey(AuthPrincipal principal, String viewerSession) {
		if (principal != null) {
			return "u:" + principal.userId();
		}
		if (viewerSession == null || viewerSession.isBlank() || viewerSession.length() > MAX_SESSION_ID_LENGTH) {
			// 빈 값이 통과하면 모든 익명이 member 하나로 뭉개진다 — 도메인 에러코드 신설 없이 공통 400 (D4)
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		return "s:" + viewerSession;
	}
}
