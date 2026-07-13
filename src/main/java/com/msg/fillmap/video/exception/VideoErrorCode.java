package com.msg.fillmap.video.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 영상 도메인 에러 코드 — developCode 3xxx 대역 (auth=2xxx 회피).
 * MSG-64(presigned)·71(교체)·72(삭제)에서 상수 확장 예정.
 */
@Getter
@AllArgsConstructor
public enum VideoErrorCode implements ErrorCodeIfs {

	INVALID_COORDINATE(3400, HttpStatus.BAD_REQUEST, "서비스 지역 범위를 벗어난 좌표입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
