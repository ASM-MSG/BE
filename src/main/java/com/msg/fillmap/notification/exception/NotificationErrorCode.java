package com.msg.fillmap.notification.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * notification 도메인 에러 코드 — developCode 9xxx 대역 신설 (MSG-178 확정. 1xxx user·2xxx auth·
 * 3xxx video·4xxx grid·5xxx search·6xxx region·7xxx badge·8xxx hotzone 다음 빈 대역 — 위키 8xxx 제안은
 * hotzone 점유로 기각). 해제는 멱등이라 404류 코드를 만들지 않는다.
 */
@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements ErrorCodeIfs {

	INVALID_PLATFORM(9400, HttpStatus.BAD_REQUEST, "platform 은 IOS, ANDROID, WEB 중 하나여야 합니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
