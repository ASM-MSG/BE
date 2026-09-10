package com.msg.fillmap.auth.support;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/**
 * 카카오 로그인 nonce 쿠키 조립 헬퍼 (MSG-345). 발급 API 가 심고, 로그인 성공 시 즉시 만료시킨다.
 *
 * <p>이 쿠키가 nonce 대조의 기대값을 <b>요청 밖</b>에 두는 장치다. 값이 요청 body 로 함께 오면 공격자도
 * 자기 값을 보낼 수 있어 자기 증명에 그치지만, 쿠키는 크로스사이트 페이지가 읽지도 심지도 못하므로
 * 브라우저가 결속을 보증한다. 서버는 발급값을 저장하지 않는다(무상태).
 *
 * <p>고정 속성: {@code HttpOnly; Path=/api/auth/oauth/kakao}, 수명 10분.
 *
 * <p>{@link RefreshTokenCookies} 의 {@code Secure; SameSite=None} 고정을 그대로 쓰지 않는다. 그 쿠키는
 * 로컬 웹 개발을 앱 모드(body 전달)로 우회하는 선행 결정이 있었지만(MSG-135), nonce 쿠키는 보안 장치
 * 자체라 우회 경로를 둘 수 없다. Secure 쿠키는 http 로컬에서 브라우저가 저장하지 않아 웹 로그인이 전부
 * 2423 으로 죽는다. 그래서 {@code secure} 플래그로 갈린다 — 켜면 배포용 {@code Secure; SameSite=None},
 * 끄면 http 로컬용 {@code SameSite=Lax}(localhost 5173↔8080 은 같은 사이트라 Lax 로 실려 온다).
 */
public final class NonceCookies {

	public static final String COOKIE_NAME = "OAUTH_NONCE";

	private static final String COOKIE_PATH = "/api/auth/oauth/kakao";
	private static final String SAME_SITE_NONE = "None";
	private static final String SAME_SITE_LAX = "Lax";
	private static final Duration MAX_AGE = Duration.ofMinutes(10);

	private NonceCookies() {
	}

	public static String issue(String nonce, boolean secure) {
		return builder(nonce, secure).maxAge(MAX_AGE).build().toString();
	}

	public static String expire(boolean secure) {
		return builder("", secure).maxAge(Duration.ZERO).build().toString();
	}

	private static ResponseCookie.ResponseCookieBuilder builder(String value, boolean secure) {
		return ResponseCookie.from(COOKIE_NAME, value)
			.httpOnly(true)
			.secure(secure)
			.sameSite(secure ? SAME_SITE_NONE : SAME_SITE_LAX)
			.path(COOKIE_PATH);
	}
}
