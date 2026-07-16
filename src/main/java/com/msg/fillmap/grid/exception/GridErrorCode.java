package com.msg.fillmap.grid.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 격자 도메인 에러 코드 — developCode 4xxx 대역.
 * (auth=2xxx, video=3xxx 이미 사용 중이라 겹치지 않는 대역을 쓴다 — MSG-73 §계약 변경).
 */
@Getter
@AllArgsConstructor
public enum GridErrorCode implements ErrorCodeIfs {

	INVALID_GRID_ID(4400, HttpStatus.BAD_REQUEST, "올바르지 않은 격자 식별자입니다"),
	INVALID_VIEWPORT(4401, HttpStatus.BAD_REQUEST, "유효하지 않은 지도 범위입니다"),
	VIEWPORT_TOO_LARGE(4402, HttpStatus.BAD_REQUEST, "조회 범위가 너무 넓습니다"),
	INVALID_CURSOR(4403, HttpStatus.BAD_REQUEST, "유효하지 않은 커서입니다"),
	INVALID_PAGE_SIZE(4404, HttpStatus.BAD_REQUEST, "페이지 크기가 허용 범위를 벗어났습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
