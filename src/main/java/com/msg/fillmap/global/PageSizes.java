package com.msg.fillmap.global;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 페이지 크기 정책 두 가지. 서비스 10곳이 같은 상수와 검증식을 각자 들고 있던 것을 모았다
 * (MSG-584, 2026-09-07 멘토링 "같은 의미 상수는 공통으로").
 *
 * <ul>
 *   <li>커서 목록(격자·전역·행사 영상 피드, 댓글, 알림함): 범위 밖은 에러가 아니라 클램프한다 (MSG-237 §D5,
 *       MSG-156 LEAST clamp 선례). 미지정·0 이하는 기본값, 상한 초과만 자른다.</li>
 *   <li>관리자 오프셋 목록(신고·계정 발급·등재 심사 큐): 범위 밖은 400 이다. PageRequest.of 에 그냥 넘기면
 *       IllegalArgumentException 이 catch-all 핸들러에서 500 이 되고, 오프셋(page*size)이 int 를 넘는 극단
 *       양수도 JPA firstResult 가 int 라 같은 500 이 된다 — 클라이언트 잘못이라 먼저 거른다.</li>
 * </ul>
 */
public final class PageSizes {

	public static final int CURSOR_DEFAULT = 20;
	public static final int CURSOR_MAX = 50;
	public static final int ADMIN_MIN = 1;
	public static final int ADMIN_MAX = 100;

	private PageSizes() {
	}

	public static int clampCursor(int size) {
		return size < 1 ? CURSOR_DEFAULT : Math.min(size, CURSOR_MAX);
	}

	/** 도메인별 에러 코드(대역이 다르다)를 받아 그 코드로 던진다. */
	public static void requireAdminRange(int page, int size, ErrorCodeIfs errorCode) {
		if (page < 0 || size < ADMIN_MIN || size > ADMIN_MAX || (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(errorCode);
		}
	}
}
