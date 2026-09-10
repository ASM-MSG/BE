package com.msg.fillmap.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.web.PasswordChangeGateInterceptor;

/**
 * MVC 인터셉터 등록 (MSG-497). 이 프로젝트의 첫 인터셉터라 등록 클래스도 여기서 생긴다.
 *
 * <p>차단 범위는 {@code /api/org/**} 뿐이다 — 비밀번호 상태 조회·변경(탈출구), 내 프로필 조회,
 * 로그아웃은 게이트 밖이라 첫 로그인 흐름이 성립한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	private final PasswordChangeGateInterceptor passwordChangeGateInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(passwordChangeGateInterceptor).addPathPatterns("/api/org/**");
	}
}
