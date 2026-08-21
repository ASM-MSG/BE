package com.msg.fillmap.event.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.event.dto.EventNotificationResponseDto;
import com.msg.fillmap.event.dto.EventNotificationUpdateRequestDto;
import com.msg.fillmap.event.service.EventNotificationService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 행사 알림 구독 API (MSG-442). 조회 세 경로(MSG-439)와 달리 쓰기라 로그인이 필요하다 —
 * SecurityConfig 의 행사 permitAll 이 GET 한정이라 이 경로는 별도 등록 없이 인증 대상이다.
 */
@Tag(name = "행사 (Events)", description = "지도 홈 행사 칩·행사방 헤더·행사 위치 목록 조회 API.")
@RestController
@RequiredArgsConstructor
public class EventNotificationController {

	private final EventNotificationService eventNotificationService;

	@Operation(
		summary = "행사 알림 구독 토글",
		description = "행사 회차 단위로 알림을 켜고 끈다. 행사방에는 참여 절차가 없어 구독이 사용자와 행사가 "
			+ "맺는 관계의 전부다. 같은 값을 반복 요청해도 같은 결과로 성공한다.\n\n"
			+ "응답의 enabled 는 저장된 구독 행의 존재가 아니라 **노출 상태**다 — 구독 행이 있으면서 회차가 "
			+ "예정이거나 진행 중일 때만 true 이고, 종료된 회차는 행이 남아 있어도 false 다(종료 시점부터 "
			+ "즉시 OFF, 정리 배치를 기다리지 않는다).\n\n"
			+ "종료된 행사(업로드 유예·아카이브)에 켜기를 요청하면 409 + developCode 13422 다 — 시작 알림이 "
			+ "이미 지나 받을 것이 없기 때문이다. 끄기는 상태와 무관하게 언제나 성공한다. 없는 회차이거나 "
			+ "아직 노출 기간 전인 예정 회차면 404 + developCode 13404 다.\n\n"
			+ "실제 발송은 이 구독 위에 알림 설정의 EVENT 카테고리 스위치가 겹쳐 결정된다 — 카테고리를 끈 "
			+ "사용자에게는 구독이 켜져 있어도 발송되지 않는다."
	)
	@PutMapping("/api/event-occurrences/{occurrenceId}/notification")
	public SuccessResponse<EventNotificationResponseDto> updateSubscription(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long occurrenceId,
		@Valid @RequestBody EventNotificationUpdateRequestDto request
	) {
		return SuccessResponse.of(eventNotificationService.updateSubscription(
			principal.userId(), occurrenceId, request.enabled()));
	}
}
