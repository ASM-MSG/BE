package com.msg.fillmap.route.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * AI 경로 추천 에러 코드 — developCode 14xxx 대역 (MSG-457 배정, response-pattern.md 대역 표가 정본).
 * 뷰포트 두 코드는 mission·event 와 같은 판정 상한을 쓰되 넓이 0(min == max)도 거부한다 — 추천은 빈
 * 범위에서 성립하지 않는다. 14502 는 AI 호출 실패 전부와 응답 형태 위반의 단일 수렴이고(FR-ROUTE-08,
 * 부분 채택·지어낸 대체 결과 없음), 14503 은 플래그 꺼짐의 명시적 비활성 응답이다 — 컨트롤러·서비스를
 * 조건부 빈으로 만들어 404 를 주면 FE 가 "기능 없음"과 "기능 꺼짐"을 구분하지 못한다.
 * 14402 는 뷰포트 14400 과 같은 방식으로 SRS 2.4 좌표 제약을 시행한다 (FE 분기 단일화, MSG-483).
 * 14504 는 14503 선례와 같은 명시적 비활성이다 (보행 경로 게이트 route.walk.enabled).
 */
@Getter
@AllArgsConstructor
public enum RouteErrorCode implements ErrorCodeIfs {

	INVALID_VIEWPORT(14400, HttpStatus.BAD_REQUEST, "유효하지 않은 지도 범위입니다"),
	VIEWPORT_TOO_LARGE(14401, HttpStatus.BAD_REQUEST, "조회 범위가 너무 넓습니다"),
	INVALID_WALK_SEGMENTS(14402, HttpStatus.BAD_REQUEST, "유효하지 않은 구간 목록입니다"),
	ROUTE_RATE_LIMITED(14429, HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요"),
	ROUTE_AI_UNAVAILABLE(14502, HttpStatus.BAD_GATEWAY, "동선 해석이 일시적으로 어렵습니다. 잠시 후 다시 시도해주세요"),
	ROUTE_DISABLED(14503, HttpStatus.SERVICE_UNAVAILABLE, "AI 경로 추천이 꺼져 있습니다"),
	ROUTE_WALK_DISABLED(14504, HttpStatus.SERVICE_UNAVAILABLE, "보행 경로 표시가 꺼져 있습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
