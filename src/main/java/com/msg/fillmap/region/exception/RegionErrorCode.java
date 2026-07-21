package com.msg.fillmap.region.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * region 도메인 에러 코드 — developCode 6xxx 대역 (auth=2xxx, video=3xxx, grid=4xxx 와 겹치지 않는 대역).
 * no-match(포함 행정동 없음)는 예외가 아니라 200 + null 이므로 여기 두지 않는다(§D3).
 * 6404 는 MSG-156 REGION_NOT_FOUND 예약이라 본 티켓에서 정의하지 않는다.
 */
@Getter
@AllArgsConstructor
public enum RegionErrorCode implements ErrorCodeIfs {

	INVALID_COORDINATE(6400, HttpStatus.BAD_REQUEST, "서비스 지역 범위를 벗어난 좌표입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
