package com.msg.fillmap.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements ErrorCodeIfs {

	EMAIL_ALREADY_EXISTS(1409, HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다"),
	USER_NOT_FOUND(1404, HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다"),
	// 프로필 이미지 (MSG-373) — video 의 3401·3402·3413·3415 를 user 대역으로 미러링한 것이라 뒷자리가 같다.
	INVALID_IMAGE_KEY(1401, HttpStatus.BAD_REQUEST, "유효하지 않은 업로드 키입니다"),
	IMAGE_UPLOAD_NOT_FOUND(1402, HttpStatus.BAD_REQUEST, "업로드된 파일을 찾을 수 없습니다"),
	IMAGE_TOO_LARGE(1413, HttpStatus.BAD_REQUEST, "프로필 이미지는 5MB 이하여야 합니다"),
	UNSUPPORTED_IMAGE_EXTENSION(1415, HttpStatus.BAD_REQUEST, "지원하지 않는 이미지 확장자입니다 (jpg, png, webp)"),
	// 위치 동의 철회 불가 (2026-08-19 팀 합의로 FR-USER-14 개정) — false 는 형식상 유효값이라
	// Bean Validation 으로 못 잡고 서비스에서 던진다. 1409 는 이메일 중복이 선점해 1400 을 쓴다.
	LOCATION_CONSENT_IRREVOCABLE(1400, HttpStatus.BAD_REQUEST, "위치기반서비스 이용 동의는 철회할 수 없습니다"),
	// 아이디 변경 요청 (MSG-497 FR-23) — 요청 이메일이 다른 계정에 쓰이는지는 검사하지 않는다.
	// 검사하면 이 API 가 타 계정 존재 오라클이 되고, 정합은 관리자 승인 시점에 확정하면 된다.
	EMAIL_CHANGE_SAME_AS_CURRENT(1420, HttpStatus.BAD_REQUEST, "현재 아이디와 같은 이메일입니다"),
	// 계정 발급 (MSG-499 FR-1·2·6). 이메일 중복은 1409, 없는 사용자는 1404 를 그대로 재사용한다.
	// "요청이 변경됨"이 1426 인 것은 1424 를 상태 필터가 선점해서다(번호 순서가 곧 도입 순서는 아니다).
	ORG_ACCOUNT_REQUEST_NOT_FOUND(1421, HttpStatus.NOT_FOUND, "존재하지 않는 발급 요청입니다"),
	ORG_ACCOUNT_REQUEST_ALREADY_PROCESSED(1422, HttpStatus.CONFLICT, "이미 처리된 발급 요청입니다"),
	INITIAL_PASSWORD_RESEND_NOT_ALLOWED(1423, HttpStatus.CONFLICT, "초기 비밀번호 재발송 대상이 아닌 계정입니다"),
	INVALID_ORG_REQUEST_STATUS(1424, HttpStatus.BAD_REQUEST, "지원하지 않는 상태 필터입니다"),
	INVALID_PAGE_RANGE(1425, HttpStatus.BAD_REQUEST, "페이지 번호 또는 크기가 유효하지 않습니다"),
	ORG_ACCOUNT_REQUEST_MODIFIED(1426, HttpStatus.CONFLICT, "검토한 이후 요청 내용이 변경되었습니다"),
	// 아이디 변경 요청 심사 (MSG-500 §API 7). 발급 요청용 1421·1422·1426 을 재사용하지 않는 이유는
	// 그 메시지가 "발급 요청"을 지목해 이 요청에 어긋나기 때문이다 — 코드는 셋 다 같은 판정의 쌍둥이다.
	// 이메일 중복은 1409, 없는 사용자는 1404, 상태·페이지 검증은 1424·1425 를 그대로 쓴다(같은 도메인).
	EMAIL_CHANGE_REQUEST_NOT_FOUND(1427, HttpStatus.NOT_FOUND, "존재하지 않는 아이디 변경 요청입니다"),
	EMAIL_CHANGE_REQUEST_ALREADY_PROCESSED(1428, HttpStatus.CONFLICT, "이미 처리된 아이디 변경 요청입니다"),
	EMAIL_CHANGE_REQUEST_MODIFIED(1429, HttpStatus.CONFLICT, "검토한 이후 요청 내용이 변경되었습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
