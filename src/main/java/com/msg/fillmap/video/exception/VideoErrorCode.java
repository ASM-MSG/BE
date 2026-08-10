package com.msg.fillmap.video.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 영상 도메인 에러 코드 — developCode 3xxx 대역 (auth=2xxx 회피).
 * 대역 규칙은 3{의미상 HTTP status} — AuthErrorCode(2401/2422) 와 같은 방식이다.
 * MSG-71(교체)에서 GRID_MISMATCH(3422) 등 확장 예정.
 */
@Getter
@AllArgsConstructor
public enum VideoErrorCode implements ErrorCodeIfs {

	INVALID_COORDINATE(3400, HttpStatus.BAD_REQUEST, "서비스 지역 범위를 벗어난 좌표입니다"),
	INVALID_S3_KEY(3401, HttpStatus.BAD_REQUEST, "유효하지 않은 업로드 키입니다"),
	UPLOAD_NOT_FOUND(3402, HttpStatus.BAD_REQUEST, "업로드된 파일을 찾을 수 없습니다"),
	VIDEO_FORBIDDEN(3403, HttpStatus.FORBIDDEN, "본인의 영상만 처리할 수 있습니다"),
	VIDEO_NOT_FOUND(3404, HttpStatus.NOT_FOUND, "영상을 찾을 수 없습니다"),
	ALREADY_IN_TARGET_STATUS(3409, HttpStatus.CONFLICT, "이미 해당 상태의 영상입니다"),
	FILE_TOO_LARGE(3413, HttpStatus.BAD_REQUEST, "허용 크기를 초과한 영상입니다"),
	UNSUPPORTED_EXTENSION(3415, HttpStatus.BAD_REQUEST, "지원하지 않는 영상 확장자입니다 (mp4, mov)"),
	INVALID_VISIBILITY(3420, HttpStatus.BAD_REQUEST, "visibility 는 PUBLIC, PRIVATE, FRIENDS 중 하나여야 합니다"),
	GRID_MISMATCH(3422, HttpStatus.BAD_REQUEST, "같은 격자 안에서만 교체할 수 있습니다"),
	INVALID_CURSOR(3423, HttpStatus.BAD_REQUEST, "유효하지 않은 커서입니다"),
	RECORDED_AT_IN_FUTURE(3424, HttpStatus.BAD_REQUEST, "촬영 시각이 현재 시각보다 미래입니다"),
	HIGHLIGHT_SOURCE_TOO_LONG(3425, HttpStatus.BAD_REQUEST, "3분 이하 영상만 하이라이트 분석이 가능합니다"),
	HIGHLIGHT_SOURCE_UNREADABLE(3426, HttpStatus.BAD_REQUEST, "영상 파일을 읽을 수 없습니다. 다른 파일로 시도해주세요"),
	// AI 연결 실패·타임아웃·5xx·파싱 불능·비활성 환경 단일 수렴 — SEARCH_UPSTREAM_ERROR(5502) 선례 (MSG-351)
	HIGHLIGHT_UPSTREAM_ERROR(3502, HttpStatus.BAD_GATEWAY, "하이라이트 분석이 일시적으로 어렵습니다. 구간을 직접 지정해주세요"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
