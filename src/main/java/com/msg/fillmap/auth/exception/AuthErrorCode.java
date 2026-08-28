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
	MISSING_CLIENT_TYPE_HEADER(2434, HttpStatus.BAD_REQUEST, "X-Client-Type 헤더가 필요합니다"),
	// 행사 운영자 계정 보안 (MSG-497). 2442 를 INVALID_CREDENTIALS(2411, 401)로 겸하지 않는 이유:
	// 로그인 상태에서 401 을 내면 클라이언트 공통 처리(토큰 만료 재발급 루프)가 오동작한다.
	PASSWORD_CHANGE_REQUIRED(2441, HttpStatus.FORBIDDEN, "초기 비밀번호를 변경해야 이용할 수 있습니다"),
	CURRENT_PASSWORD_MISMATCH(2442, HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다"),
	INVALID_RESET_TOKEN(2443, HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 재설정 링크입니다"),
	NEW_PASSWORD_SAME_AS_CURRENT(2444, HttpStatus.BAD_REQUEST, "새 비밀번호가 현재 비밀번호와 같습니다"),
	PASSWORD_NOT_SET(2445, HttpStatus.BAD_REQUEST, "비밀번호가 설정되지 않은 계정입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
