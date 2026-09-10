package com.msg.fillmap.friend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * friend 도메인 에러 코드 — developCode 9xxx 대역 신설 (1xxx user·2xxx auth·3xxx video·4xxx grid·
 * 5xxx search·6xxx region·7xxx badge·8xxx hotzone 다음 빈 대역, MSG-185). 뒷자리는 HTTP 상태 에코가
 * 기본이되 같은 상태가 겹치면 의미 구분을 우선한다 (9409/9410 CONFLICT, 9404/9414/9424 NOT_FOUND —
 * VideoErrorCode 3413·3415 선례).
 */
@Getter
@AllArgsConstructor
public enum FriendErrorCode implements ErrorCodeIfs {

	SELF_FRIEND_REQUEST(9400, HttpStatus.BAD_REQUEST, "자기 자신에게는 친구 요청을 보낼 수 없습니다"),
	FRIEND_CODE_NOT_FOUND(9404, HttpStatus.NOT_FOUND, "존재하지 않는 친구 코드입니다"),
	ALREADY_FRIENDS(9409, HttpStatus.CONFLICT, "이미 친구인 사용자입니다"),
	FRIEND_REQUEST_ALREADY_PENDING(9410, HttpStatus.CONFLICT, "이미 보낸 친구 요청이 대기 중입니다"),
	FRIEND_REQUEST_NOT_FOUND(9414, HttpStatus.NOT_FOUND, "해당 친구 요청이 없습니다"),
	INVALID_FRIEND_SORT(9420, HttpStatus.BAD_REQUEST, "sort 는 recent 또는 nickname 이어야 합니다"),
	FRIENDSHIP_NOT_FOUND(9424, HttpStatus.NOT_FOUND, "친구 관계가 없습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
