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
	MISSION_NOT_FOUND(12404, HttpStatus.NOT_FOUND, "미션을 찾을 수 없습니다"),
	INVALID_AGGREGATION_UNIT(12405, HttpStatus.BAD_REQUEST, "지원하지 않는 집계 단위입니다"),
	/**
	 * 미션 경유 업로드가 받아들여질 수 없는 모든 상태 (MSG-459 D-5·D-10). 없는 미션, 코스·구역·테마·상시
	 * 유형, 활성 기간 밖, 촬영 시각이 미션 기간 밖, 대표 격자 없음, 이미 다른 자리에 확정된 키, S3 에 없는
	 * 키, 크기 초과가 <b>한 응답으로 수렴한다</b> — 사유를 갈라 주면 요청 두 벌로 미션 존재를 알아내는
	 * 오라클이 열리기 때문이다(FR-10). 값싼 순수 검증 둘(미래 촬영 시각 3424 · 키 형식과 소유 접두어 3401)만
	 * 미션 조회보다 앞에서 정확한 코드로 답한다. 409 인 것은 권한이 아니라 미션의 현재 상태와 요청이
	 * 충돌하는 거절이라서다.
	 */
	MISSION_UPLOAD_UNAVAILABLE(12409, HttpStatus.CONFLICT, "지금은 이 미션에 영상을 올릴 수 없습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
