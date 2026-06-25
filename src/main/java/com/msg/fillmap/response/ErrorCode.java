package com.msg.fillmap.response;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode implements ErrorCodeIfs {

	OK(200, HttpStatus.OK, "성공"),

	BAD_REQUEST(400, HttpStatus.BAD_REQUEST, "잘못된 요청"),
	UNAUTHORIZED(401, HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
	FORBIDDEN(403, HttpStatus.FORBIDDEN, "접근 권한이 없습니다"),
	NOT_FOUND(404, HttpStatus.NOT_FOUND, "존재하지 않는 리소스"),

	INTERNAL_SERVER_ERROR(500, HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}