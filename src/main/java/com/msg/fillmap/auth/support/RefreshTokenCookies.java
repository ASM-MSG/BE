package com.msg.fillmap.auth.support;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/**
 * 웹 클라이언트용 리프레시 토큰 쿠키 조립 헬퍼 (MSG-135).
 *
 * <p>속성 고정: {@code HttpOnly; Secure; SameSite=None; Path=/api/auth/reissue}.
 * SameSite=None + Secure 는 https 전용이므로 로컬 웹 개발은 앱 모드(body)로 진행한다 (확정 결정 8).
 */
public final class RefreshTokenCookies {

	public static final String COOKIE_NAME = "refreshToken";

	private static final String COOKIE_PATH = "/api/auth/reissue";
	private static final String SAME_SITE_NONE = "None";

	private RefreshTokenCookies() {
	}

	public static String issue(String refreshToken, Duration maxAge) {
		return builder(refreshToken).maxAge(maxAge).build().toString();
	}

	public static String expire() {
		return builder("").maxAge(Duration.ZERO).build().toString();
	}

	private static ResponseCookie.ResponseCookieBuilder builder(String value) {
		return ResponseCookie.from(COOKIE_NAME, value)
			.httpOnly(true)
			.secure(true)
			.sameSite(SAME_SITE_NONE)
			.path(COOKIE_PATH);
	}
}
