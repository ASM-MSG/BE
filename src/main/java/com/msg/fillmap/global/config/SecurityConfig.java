package com.msg.fillmap.global.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.auth.jwt.CustomAccessDeniedHandler;
import com.msg.fillmap.auth.jwt.CustomAuthenticationEntryPoint;
import com.msg.fillmap.auth.jwt.JwtAuthenticationFilter;
import com.msg.fillmap.auth.jwt.TokenProvider;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public JwtAuthenticationFilter jwtAuthenticationFilter(
		TokenProvider tokenProvider,
		@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver
	) {
		return new JwtAuthenticationFilter(tokenProvider, resolver);
	}

	@Bean
	public CustomAuthenticationEntryPoint customAuthenticationEntryPoint(ObjectMapper objectMapper) {
		return new CustomAuthenticationEntryPoint(objectMapper);
	}

	@Bean
	public CustomAccessDeniedHandler customAccessDeniedHandler(ObjectMapper objectMapper) {
		return new CustomAccessDeniedHandler(objectMapper);
	}

	@Bean
	public SecurityFilterChain filterChain(
		HttpSecurity http,
		JwtAuthenticationFilter jwtAuthenticationFilter,
		CustomAuthenticationEntryPoint authenticationEntryPoint,
		CustomAccessDeniedHandler accessDeniedHandler,
		CorsConfigurationSource corsConfigurationSource
	) throws Exception {
		return http
			.cors(cors -> cors.configurationSource(corsConfigurationSource))
			.csrf(csrf -> csrf.disable())
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/oauth/**").permitAll()
				// 토큰 재발급(MSG-135) — 액세스 만료 상태에서 호출되므로 인증 불필요 (리프레시가 자격 증명)
				.requestMatchers("/api/auth/reissue").permitAll()
				// [로컬/dev 전용] 소셜 로그인 모의 (MSG-135) — prod 프로파일엔 컨트롤러 자체가 없음
				.requestMatchers("/api/auth/dev/**").permitAll()
				// 관측(MSG-128·MSG-344) — Prometheus scrape 용 permitAll 은 유지한다. 앱 레벨
				// IP 검사는 기각(X-Forwarded-For 위조 가능) — prod 는 관리 포트 분리
				// (application-prod.yml 의 management.server.port) + 보안그룹이 접근을 막고,
				// 공개 포트에서 /actuator/** 는 404 다. 로컬·dev 는 지금 그대로 굴러간다.
				.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
				// API 문서(MSG-131) — Swagger UI · OpenAPI 스펙. prod 노출 정책은 별도 검토.
				.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
				// 관리자 API(MSG-195) — 이 프로젝트 유일한 role 인가 지점. JWT role 클레임이 심는
				// ROLE_ADMIN 권한을 여기서 처음 소비한다. 관리자 API 가 URL 프리픽스 하나로 다 묶여
				// 메서드 보안(@EnableMethodSecurity) 없이 matcher 한 줄이면 충분하다.
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				.anyRequest().authenticated()
			)
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(
		@Value("${cors.allowed-origins}") List<String> allowedOrigins
	) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		// X-Device-Id — 서버가 생성한 디바이스 id 를 클라이언트가 응답 헤더로 읽어야 한다 (MSG-135)
		config.setExposedHeaders(List.of("Authorization", "X-Device-Id"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
