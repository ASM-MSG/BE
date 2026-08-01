package com.msg.fillmap.hotzone.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 핫구역 도메인 에러 코드 — developCode 8xxx 대역 (1xxx user · 2xxx auth · 3xxx video · 4xxx grid ·
 * 5xxx search · 6xxx region · 7xxx badge 다음 빈 대역, MSG-233 D5).
 * 파라미터 누락은 전역 400, 면적 상한 없음(결과가 K로 이미 상한) — MVP 상수는 하나다.
 */
@Getter
@AllArgsConstructor
public enum HotZoneErrorCode implements ErrorCodeIfs {

	INVALID_VIEWPORT(8400, HttpStatus.BAD_REQUEST, "유효하지 않은 지도 범위입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
