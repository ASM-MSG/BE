package com.msg.fillmap.badge.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * badge 도메인 에러 코드 — developCode 7xxx 대역 신설 (1xxx user·2xxx auth·3xxx video·4xxx grid·
 * 5xxx search·6xxx region 다음 빈 대역, MSG-239 §D7). 3개 이상 지정은 @Size → global 400 이라
 * 신규 코드가 아니고, 미획득은 존재/보유를 구분하지 않는다(남의 획득 여부 탐색 방지 겸 단순화 — §D6).
 */
@Getter
@AllArgsConstructor
public enum BadgeErrorCode implements ErrorCodeIfs {

	INVALID_FEATURED_BADGES(7400, HttpStatus.BAD_REQUEST, "대표 뱃지 요청이 올바르지 않습니다"),
	BADGE_NOT_EARNED(7403, HttpStatus.FORBIDDEN, "획득한 뱃지만 대표로 설정할 수 있습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
