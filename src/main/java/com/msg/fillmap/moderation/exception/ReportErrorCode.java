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
 * <p>404 는 여기에 없다 — 영상 없음·DELETED·BLINDED 는 재생 경로와 같은
 * {@code VideoErrorCode.VIDEO_NOT_FOUND}(3404)를 재사용해 존재 은닉 응답을 한 벌로 유지한다 (§D1).
 */
@Getter
@AllArgsConstructor
public enum ReportErrorCode implements ErrorCodeIfs {

	INVALID_REASON(11400, HttpStatus.BAD_REQUEST,
		"reason 은 INAPPROPRIATE, PRIVACY, SPAM, COPYRIGHT, OTHER 중 하나여야 합니다"),
	DETAIL_REQUIRED(11401, HttpStatus.BAD_REQUEST, "OTHER 사유는 상세 설명이 필요합니다"),
	SELF_REPORT(11402, HttpStatus.BAD_REQUEST, "자기 영상은 신고할 수 없습니다"),
	DUPLICATE_REPORT(11409, HttpStatus.CONFLICT, "이미 접수된 신고입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
