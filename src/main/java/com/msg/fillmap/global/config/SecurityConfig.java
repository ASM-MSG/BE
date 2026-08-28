package com.msg.fillmap.global.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
				// 행사방 열람 인원(MSG-443 D4) — 열람 계열이라 비로그인 허용. JwtAuthenticationFilter 의
				// PUBLIC_AUTH_PATHS 에는 넣지 않는다 — 인증 불요이지 토큰 무시가 아니라, 토큰이 실려
				// 오면 필터가 평소처럼 principal 을 세워야 로그인 사용자가 u:{userId} 로 집계된다.
				.requestMatchers("/api/event-occurrences/*/heartbeat", "/api/event-occurrences/*/viewer-count")
				.permitAll()
				// API 문서(MSG-131) — Swagger UI · OpenAPI 스펙. prod 노출 정책은 별도 검토.
				.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
				// 행사 조회(MSG-439) — 비로그인 열람 허용("상단 칩은 비로그인, 업로드는 로그인").
				// GET 한정 등록이 계약의 일부다: 메서드 무제한 문자열 패턴으로 열면 같은 경로에 붙을 쓰기
				// API(업로드 MSG-440 · 댓글 MSG-441 · 알림 MSG-442)까지 조용히 인증 없이 열린다.
				// 경로도 정확히 넷만 적는다 — /api/grids/** 로 넓히면 격자 단일 조회의 인증이 함께 풀린다.
				.requestMatchers(HttpMethod.GET,
					"/api/event-occurrences",
					"/api/event-occurrences/*",
					"/api/event-occurrences/*/locations",
					"/api/grids/*/event-locations").permitAll()
				// 행사 영상 조회(MSG-440) — 위 조회 4종과 같은 규칙이다. 피드 경로는 업로드 POST 와 URL 이
				// 같아 메서드 무제한으로 열면 업로드까지 익명에 풀린다 — GET 한정이 계약의 일부다.
				// 댓글 목록(MSG-441)도 같은 규칙이다 — 위 "/api/event-videos/*" 는 세그먼트 하나만 매치해
				// 하위 경로를 덮지 않으므로 한 줄을 더 적는다. 여기서도 GET 한정이 계약이다: 문자열
				// 패턴으로 열면 같은 경로의 댓글 작성 POST 가 익명에 함께 풀린다.
				.requestMatchers(HttpMethod.GET,
					"/api/event-occurrences/*/locations/*/videos",
					"/api/event-videos/*",
					"/api/event-videos/*/comments").permitAll()
				// 상세의 명시 HEAD 매핑(부수효과 없는 200)은 GET 한정 matcher 에 안 잡혀 익명 HEAD 가 401 이
				// 된다 — 같은 경로를 HEAD 로도 연다. HEAD 응답은 전 id 동일이라 존재 오라클이 아니다.
				.requestMatchers(HttpMethod.HEAD, "/api/event-videos/*").permitAll()
				// 핫구역·미션 조회(MSG-454) — 위 행사 조회와 같은 "상단 칩은 비로그인, 업로드는 로그인"
				// 원칙의 적용이다(정본 docs/prd/mission-map-explore.md 8절). GET 한정이 계약의 일부인 것도
				// 같은 이유다. 상세·영상 두 줄만 {missionId:[0-9]+} 제약 변수를 쓴다: 제약 없는
				// /api/missions/* 는 progress 같은 리터럴 형제와 미래의 한 세그먼트 GET 경로까지 기본
				// 공개로 만드는 fail-open 이라서다. 숫자 제약이면 문자 경로는 이 matcher 에 안 잡혀
				// anyRequest().authenticated() 로 떨어진다(기본이 인증 쪽, NFR-SEC-01).
				// 격자 미션 역조회(MSG-459)도 같은 열람 계열이다. 경로를 정확히 한 줄만 적는 것은 위
				// 행사 역조회와 같은 이유다 — /api/grids/** 로 넓히면 격자 단일 조회의 인증이 함께 풀린다.
				.requestMatchers(HttpMethod.GET,
					"/api/hotzones",
					"/api/hotzones/aggregation",
					"/api/missions/active",
					"/api/missions/aggregation",
					"/api/missions/{missionId:[0-9]+}",
					"/api/missions/{missionId:[0-9]+}/videos",
					"/api/grids/*/missions").permitAll()
				// 행정동 조회(MSG-467): 위 핫구역·미션 개방(MSG-454)과 같은 "상단 칩은 비로그인" 원칙의
				// 잔여분이다(정본 docs/prd/mission-map-explore.md 8절 2026-08-24). 칩 좌측 패널이 위치줄과
				// 지역 필터를 이 둘로 그린다. GET 한정과 경로 열거가 계약의 일부다: /api/regions/** 로 넓히면
				// 내 수집률 stats 계열 4종의 인증이 함께 풀리고, 그 넷은 principal.userId() 를 바로 부르므로
				// 익명 유입 시 NPE 500 이 된다. 둘 다 사용자 무관 값이라(FR-REGION-02·15) 응답 계약은 불변.
				.requestMatchers(HttpMethod.GET,
					"/api/regions/reverse-geocode",
					"/api/regions/districts").permitAll()
				// 비로그인 지도의 나머지 조회(MSG-469): 위 행정동 개방(MSG-467)과 같은 "상단 칩은 비로그인,
				// 업로드는 로그인" 원칙의 마지막 잔여분이다(정본 docs/prd/mission-map-explore.md 8절 2026-08-25).
				// 구역 목록은 검색바에서 구역 이름("서면")으로 지도를 옮기는 기능의 재료이고(MSG-234 §D6,
				// FR-ZONE-11), 격자 커버와 전역 영상 목록과 시간대 분포는 격자를 눌렀을 때 화면을 채우는
				// 값이다(셋의 노출 자리가 같아 하나만 빠지면 그 자리만 빈다). 여섯 다 사용자 무관 값이라 응답
				// 계약은 불변이다(FR-ZONE-11, FR-SEARCH-01·07, FR-VIDEO-17·18, FR-MAP-09).
				// GET 한정과 경로 열거가 계약의 일부다. /api/grids/** 로 넓히면 단일 격자와 뷰포트 도감과 도감
				// 집계와 내 영상 목록까지 함께 풀리는데, 그 넷은 principal.userId() 를 null 가드 없이 부르므로
				// 익명 유입이 401 이 아니라 NPE 500 이 된다. /api/search/** 로 넓히는 것도 같은 이유로 금지다 —
				// 지금 search 하위가 둘뿐이라 안전해 보여도 다음에 붙는 경로가 기본 공개가 되는 fail-open 이 된다.
				.requestMatchers(HttpMethod.GET,
					"/api/zones",
					"/api/search/places",
					"/api/search/trending",
					"/api/grids/*/cover",
					"/api/grids/*/videos",
					"/api/grids/*/hourly-uploads").permitAll()
				// 비로그인 조회 개방의 마지막 잔여분(MSG-491): 위 MSG-469 와 같은 원칙이고, 이번으로 비로그인
				// 지도의 조회 개방이 닫힌다(정본 docs/prd/mission-map-explore.md 8절 2026-08-26). 동 격자 카드
				// 목록은 지도 홈 좌측 패널의 재료이고(FR-VIDEO-17), 재생은 그 카드를 눌렀을 때 이어지는 동작이며
				// (FR-VIDEO-12·13·16), 전체 지역 목록은 지역 탐색 화면 전체가 걸린 조회다(FR-SEARCH-15).
				// 셋 중 재생과 전체 지역 목록은 principal 을 쓰던 자리라 익명이면 null 을 받아 각각
				// "PUBLIC 만 통과"와 "개인화 절 없음"으로 떨어진다 — 열기 전에 컨트롤러의 null 전달이
				// 먼저 있어야 한다(없으면 401 이 아니라 NPE 500).
				// 경로 열거가 계약의 일부인 것도 위와 같다. /api/regions/** 로 넓히면 내 수집률 stats 계열이,
				// /api/videos/** 로 넓히면 업로드 확정·교체·삭제와 신고가 함께 풀린다. 재생 경로를 GET 한정으로
				// 여는 것이 같은 URL 의 PUT·PATCH·DELETE 를 막는 유일한 수단이다.
				// videoId 자리를 숫자로 못박는 것도 같은 fail-open 방어다 — /api/videos/* 로 두면 나중에 붙는
				// GET /api/videos/{새경로}(drafts·my 류)가 열거를 거치지 않고 기본 공개가 된다. 지금 이 하위의
				// 단일 세그먼트 GET 은 재생 하나뿐이라 동작은 그대로이고, 늘어날 때 401 로 닫히는 쪽이 기본이 된다.
				.requestMatchers(HttpMethod.GET,
					"/api/regions/*/grids",
					"/api/regions/explore",
					"/api/videos/{videoId:[0-9]+}").permitAll()
				// 재생의 명시 HEAD 매핑은 GET 한정 matcher 에 안 잡혀 익명 HEAD 가 401 이 된다 — 행사 영상
				// 상세와 같은 이유로 같은 경로를 HEAD 로도 연다(접근 제어 없이 200, 전 id 동일이라 존재 오라클 아님).
				.requestMatchers(HttpMethod.HEAD, "/api/videos/{videoId:[0-9]+}").permitAll()
				// 관리자 API(MSG-195) — 이 프로젝트 유일한 role 인가 지점. JWT role 클레임이 심는
				// ROLE_ADMIN 권한을 여기서 처음 소비한다. 관리자 API 가 URL 프리픽스 하나로 다 묶여
				// 메서드 보안(@EnableMethodSecurity) 없이 matcher 한 줄이면 충분하다.
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				// 행사 등재 콘솔(MSG-496) — 행사 운영자(ORG) 전용 프리픽스. ADMIN 에게 열지 않는다:
				// 관리자는 자기 심사 API(/api/admin/**)를 쓰고, 콘솔 API 에는 소유권 검사가 걸린다.
				.requestMatchers("/api/org/**").hasRole("ORG")
				// ORG 도 쓰는 공용 경로 2개 — 아래 catch-all 이 역할 제한이라 여기서 명시 허용해야 한다.
				// /me 는 GET 한정이다: 형제 쓰기 경로(닉네임·프로필 이미지 등)는 세그먼트가 달라 이 matcher 에
				// 안 잡히고 catch-all 로 떨어져 ORG 에게 403 이 된다.
				.requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
				.requestMatchers("/api/auth/logout").authenticated()
				// 행사 운영자 권한은 콘솔에만 미친다 (MSG-496 FR-5) — authenticated() 였으면 ORG 토큰이
				// 영상 업로드·친구 같은 일반 사용자 API 를 그대로 통과한다. 익명은 여기서도 entry point 로
				// 넘어가 지금과 같은 401 이다(403 아님).
				.anyRequest().hasAnyRole("USER", "ADMIN")
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
