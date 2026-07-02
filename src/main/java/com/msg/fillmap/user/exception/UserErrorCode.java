package com.msg.fillmap.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements ErrorCodeIfs {

	EMAIL_ALREADY_EXISTS(1409, HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다"),
	USER_NOT_FOUND(1404, HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
