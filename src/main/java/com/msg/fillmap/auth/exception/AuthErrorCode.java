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
	INVALID_AUTHORIZATION_CODE(2423, HttpStatus.UNAUTHORIZED, "유효하지 않은 인가 코드입니다"),
	OAUTH_PROVIDER_ERROR(2502, HttpStatus.BAD_GATEWAY, "소셜 로그인 제공자 오류입니다. 잠시 후 다시 시도해주세요"),
	INVALID_CREDENTIALS(2411, HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
	INVALID_REFRESH_TOKEN(2431, HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다"),
	EXPIRED_REFRESH_TOKEN(2432, HttpStatus.UNAUTHORIZED, "만료된 리프레시 토큰입니다"),
	REFRESH_TOKEN_REUSE_DETECTED(2433, HttpStatus.UNAUTHORIZED, "재사용이 감지되어 세션이 폐기되었습니다. 다시 로그인해주세요"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
