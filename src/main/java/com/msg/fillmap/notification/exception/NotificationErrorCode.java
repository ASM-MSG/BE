package com.msg.fillmap.notification.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * notification 도메인 에러 코드 — developCode 10xxx 대역 (1xxx user·2xxx auth·3xxx video·4xxx grid·
 * 5xxx search·6xxx region·7xxx badge·8xxx hotzone·9xxx friend 다음 빈 대역. MSG-178 초안의 9xxx 는
 * 병렬 레인 MSG-185 friend 와 충돌해 notification 이 10xxx 로 이동 — 성민 확정).
 * 해제는 멱등이라 404류 코드를 만들지 않는다.
 */
@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements ErrorCodeIfs {

	INVALID_PLATFORM(10400, HttpStatus.BAD_REQUEST, "platform 은 IOS, ANDROID, WEB 중 하나여야 합니다"),
	INVALID_CATEGORY(10420, HttpStatus.BAD_REQUEST,
		"category 는 BADGE, HOTZONE, REMIND, VIDEO, WEEKLY, FRIEND 중 하나여야 합니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
