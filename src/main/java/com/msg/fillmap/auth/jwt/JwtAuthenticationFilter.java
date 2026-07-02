package com.msg.fillmap.auth.jwt;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.msg.fillmap.global.exception.ApiException;

/**
 * SecurityConfig 에서 @Bean 으로 명시 등록. @Component 를 걸지 않는 이유:
 * @WebMvcTest 슬라이스가 필터를 자동 로드하면서 TokenProvider 미해결로 컨텍스트가 깨진다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final TokenProvider tokenProvider;
	private final HandlerExceptionResolver resolver;

	public JwtAuthenticationFilter(TokenProvider tokenProvider, HandlerExceptionResolver resolver) {
		this.tokenProvider = tokenProvider;
		this.resolver = resolver;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String token = extractBearerToken(request);
		if (token != null) {
			try {
				AuthPrincipal principal = tokenProvider.parseAccessToken(token);
				UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
					principal,
					null,
					List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
				);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			} catch (ApiException e) {
				SecurityContextHolder.clearContext();
				resolver.resolveException(request, response, null, e);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	private String extractBearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return null;
		}
		return header.substring(BEARER_PREFIX.length());
	}
}
