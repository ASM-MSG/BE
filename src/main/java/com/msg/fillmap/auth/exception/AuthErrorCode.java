package com.msg.fillmap.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

@Getter
@AllArgsConstructor
public enum AuthErrorCode implements ErrorCodeIfs {

	INVALID_TOKEN(2401, HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
	EXPIRED_TOKEN(2402, HttpStatus.UNAUTHORIZED, "만료된 토큰입니다"),
	UNAUTHENTICATED(2403, HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
	INVALID_ID_TOKEN(2421, HttpStatus.UNAUTHORIZED, "유효하지 않은 소셜 로그인 토큰입니다"),
	UNSUPPORTED_PROVIDER(2422, HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인 provider 입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
