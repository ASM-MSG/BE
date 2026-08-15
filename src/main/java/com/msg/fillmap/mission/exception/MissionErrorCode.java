package com.msg.fillmap.mission.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 미션 도메인 에러 코드 — developCode 12xxx 대역 (MSG-398 D6, response-pattern.md 대역 표가 정본).
 * 격자 대역(4401·4402)을 재사용하지 않는 이유: mission 이 Owner A 의 GridErrorCode 를 import 하면
 * 도메인 경계가 예외 계층에서 뚫리고, 레포 컨벤션이 "새 실패 케이스는 도메인 XxxErrorCode 에 추가"다.
 * 상수 이름과 번호 끝자리는 격자 쪽과 나란히 맞춰 대조하기 쉽게 둔다.
 */
@Getter
@AllArgsConstructor
public enum MissionErrorCode implements ErrorCodeIfs {

	INVALID_VIEWPORT(12400, HttpStatus.BAD_REQUEST, "유효하지 않은 지도 범위입니다"),
	VIEWPORT_TOO_LARGE(12401, HttpStatus.BAD_REQUEST, "조회 범위가 너무 넓습니다"),
	INVALID_MISSION_TYPE(12402, HttpStatus.BAD_REQUEST, "지원하지 않는 미션 종류입니다"),
	TOO_MANY_MISSION_IDS(12403, HttpStatus.BAD_REQUEST, "한 번에 조회할 수 있는 미션 수를 넘었습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
