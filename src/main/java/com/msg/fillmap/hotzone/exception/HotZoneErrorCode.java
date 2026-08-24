package com.msg.fillmap.hotzone.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 핫구역 도메인 에러 코드 — developCode 8xxx 대역 (1xxx user · 2xxx auth · 3xxx video · 4xxx grid ·
 * 5xxx search · 6xxx region · 7xxx badge 다음 빈 대역, MSG-233 D5).
 * 개별 조회(GET /api/hotzones)는 파라미터 누락이 전역 400 이고 면적 상한이 없다(결과가 K로 이미 상한).
 * 행정 단위 집계(GET /api/hotzones/aggregation, MSG-466)만 파라미터를 직접 검증해 아래 세 코드를 쓴다.
 * 끝자리는 미션 집계(12401·12405)와 나란히 맞춘다 — 같은 화면의 두 레이어가 같은 실패를 같은 끝자리로 알린다.
 */
@Getter
@AllArgsConstructor
public enum HotZoneErrorCode implements ErrorCodeIfs {

	INVALID_VIEWPORT(8400, HttpStatus.BAD_REQUEST, "유효하지 않은 지도 범위입니다"),
	VIEWPORT_TOO_LARGE(8401, HttpStatus.BAD_REQUEST, "조회 범위가 너무 넓습니다"),
	INVALID_AGGREGATION_UNIT(8405, HttpStatus.BAD_REQUEST, "지원하지 않는 집계 단위입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
