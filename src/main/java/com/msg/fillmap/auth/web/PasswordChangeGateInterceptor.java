package com.msg.fillmap.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 첫 로그인 비밀번호 강제 변경 게이트 (MSG-497 FR-21). 초기 비밀번호 상태의 계정은 행사 등재 콘솔
 * ({@code /api/org/**})을 쓸 수 없다.
 *
 * <p><b>화면 게이트만으로는 성립하지 않는다.</b> FR-21 의 근거가 "발급자가 초기 비밀번호를 아는
 * 상태로는 행위자를 특정할 수 없다"인데, 화면만 막으면 초기 비밀번호를 아는 사람이 API 를 직접 호출해
 * 행위할 수 있다. 그래서 서버가 막는다.
 *
 * <p>플래그를 JWT 클레임에 싣지 않고 매 요청 DB 를 보는 이유는 해제 즉시성이다 — 클레임은 발급 시점
 * 스냅숏이라 변경 직후에도 기존 토큰이 true 를 들고 있어 재발급 전까지 계속 막힌다. 각 서비스에서
 * 검사하는 방식은 콘솔 API 가 늘 때마다 복제가 필요하고 누락이 곧 우회(fail-open)다. 경로 등록 한 곳이
 * 미래의 콘솔 API 전부를 덮는 이 방식만 그 둘을 함께 피한다.
 *
 * <p>요청당 PK 조회 1회가 늘지만 대상이 콘솔(내부 소수)이라 수용한다. ORG 트래픽이 유의미해지면
 * 짧은 TTL 캐시로 승격한다.
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeGateInterceptor implements HandlerInterceptor {

	private final UserRepository userRepository;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		// 시큐리티 체인이 먼저라 이 시점엔 ORG 인증이 끝나 있어 도달할 수 없는 분기다. 그래도 통과가
		// 아니라 거절인 이유는 게이트의 기본값이 닫힘이어야 해서다 — 나중에 /api/org/** 아래에 permitAll
		// 경로가 붙으면 판정 재료(userId) 없이 게이트가 조용히 열리는 자리가 여기다.
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
			throw new ApiException(AuthErrorCode.UNAUTHENTICATED);
		}
		if (userRepository.existsByIdAndPasswordMustChangeTrue(principal.userId())) {
			// 역할 불일치 403(공통 FORBIDDEN)과 developCode 로 구분돼 화면이 변경 화면으로 보낼 수 있다.
			throw new ApiException(AuthErrorCode.PASSWORD_CHANGE_REQUIRED);
		}
		return true;
	}
}
