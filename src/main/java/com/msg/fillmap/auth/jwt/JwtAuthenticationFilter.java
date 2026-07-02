package com.msg.fillmap.auth.jwt;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.msg.fillmap.global.exception.ApiException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final TokenProvider tokenProvider;
	private final HandlerExceptionResolver resolver;

	public JwtAuthenticationFilter(
		TokenProvider tokenProvider,
		@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver
	) {
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
