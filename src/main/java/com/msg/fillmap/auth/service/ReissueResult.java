package com.msg.fillmap.auth.service;

/**
 * 토큰 재발급 내부 결과 (MSG-135) — API 응답 DTO 가 아니다.
 * 컨트롤러가 전송 분기(웹 쿠키/앱 body)와 X-Device-Id 에코에 사용한다.
 */
public record ReissueResult(String accessToken, String refreshToken, String deviceId) {
}
