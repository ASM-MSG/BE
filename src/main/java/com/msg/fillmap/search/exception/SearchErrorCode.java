package com.msg.fillmap.search.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * search 도메인 에러 코드 — developCode 5xxx 대역 신설 (user=1xxx·auth=2xxx·video=3xxx·grid=4xxx·region=64xx
 * 와 겹치지 않는 공백 대역, MSG-251 §D3). 카카오 5xx·타임아웃·4xx(키/쿼터)·파싱 실패는 전부
 * SEARCH_UPSTREAM_ERROR(5502) 하나로 수렴한다 — FE 대응(잠시 후 재시도 안내)이 같아 세분화는 소비처 없는 YAGNI.
 * q 누락(400 global)·trim 후 빈 q·무매치(200 [])는 에러가 아니므로 여기 두지 않는다.
 */
@Getter
@AllArgsConstructor
public enum SearchErrorCode implements ErrorCodeIfs {

	// ponytail: 쿼터 초과(429)가 실측되면 5429 분리(FE 백오프 신호) — 상수 1개 추가로 끝 (§D3)
	SEARCH_UPSTREAM_ERROR(5502, HttpStatus.BAD_GATEWAY, "장소 검색이 일시적으로 어렵습니다. 잠시 후 다시 시도해주세요"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
