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
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
