package com.msg.fillmap.moderation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * moderation 도메인 에러 코드 — developCode 11xxx 대역 신설 (10xxx notification 다음 빈 대역, MSG-192).
 * 뒷자리는 HTTP 상태 에코가 기본이되 같은 상태가 겹치면 의미 구분을 우선한다 (FriendErrorCode 선례).
 *
 * <p>영상 축 404 는 여기에 없다 — 영상 없음·DELETED·BLINDED 는 재생 경로와 같은
 * {@code VideoErrorCode.VIDEO_NOT_FOUND}(3404)를 재사용해 존재 은닉 응답을 한 벌로 유지한다 (MSG-192 §D1).
 * 신고 축 404(REPORT_NOT_FOUND)는 반대로 여기에 신설한다 — 신고는 관리자만 보는 자원이라 존재를 숨길
 * 상대가 없어 영상 축의 은닉 관례를 따를 이유가 없다 (MSG-195 §D6).
 */
@Getter
@AllArgsConstructor
public enum ReportErrorCode implements ErrorCodeIfs {

	INVALID_REASON(11400, HttpStatus.BAD_REQUEST,
		"reason 은 INAPPROPRIATE, PRIVACY, SPAM, COPYRIGHT, OTHER 중 하나여야 합니다"),
	DETAIL_REQUIRED(11401, HttpStatus.BAD_REQUEST, "OTHER 사유는 상세 설명이 필요합니다"),
	SELF_REPORT(11402, HttpStatus.BAD_REQUEST, "자기 영상은 신고할 수 없습니다"),
	DUPLICATE_REPORT(11409, HttpStatus.CONFLICT, "이미 접수된 신고입니다"),

	// 관리자 신고 처리 (MSG-195). 11409 를 접수 중복이 선점해 재처리 충돌은 11410 으로 비켰고
	// (9409·9410 선례), 잘못된 열거형 파라미터는 x420 선례(3420·9420)의 연장이다.
	REPORT_NOT_FOUND(11404, HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다"),
	ALREADY_PROCESSED_REPORT(11410, HttpStatus.CONFLICT, "이미 처리된 신고입니다"),
	INVALID_STATUS_FILTER(11420, HttpStatus.BAD_REQUEST,
		"status 는 PENDING, REVIEWING, RESOLVED, REJECTED 중 하나여야 합니다"),
	INVALID_PAGE_REQUEST(11421, HttpStatus.BAD_REQUEST, "page 는 0 이상, size 는 1 이상 100 이하여야 합니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
