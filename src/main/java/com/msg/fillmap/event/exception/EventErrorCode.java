package com.msg.fillmap.event.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 행사 도메인 에러 코드 — developCode 13xxx 대역 (MSG-438 배정, response-pattern.md 대역 표가 정본).
 * EVENT_NOT_FOUND 는 "없는 회차"와 "아직 노출 전인 회차"가 함께 쓴다 — 노출 전 회차에 다른 코드를 주면
 * 순차 id 대입만으로 미공개 행사의 존재가 드러나기 때문이다 (MSG-439 §API 명세 존재 은닉).
 * 뷰포트 두 코드는 mission·grid 와 같은 판정·같은 상한을 쓰고 대역만 다르다 — 도메인 예외는 도메인이 갖는다.
 */
@Getter
@AllArgsConstructor
public enum EventErrorCode implements ErrorCodeIfs {

	INVALID_VIEWPORT(13400, HttpStatus.BAD_REQUEST, "유효하지 않은 지도 범위입니다"),
	VIEWPORT_TOO_LARGE(13401, HttpStatus.BAD_REQUEST, "조회 범위가 너무 넓습니다"),
	EVENT_NOT_FOUND(13404, HttpStatus.NOT_FOUND, "행사를 찾을 수 없습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
