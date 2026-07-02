package com.msg.fillmap.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.UserRole;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

	@Mock
	private TokenProvider tokenProvider;

	@Mock
	private HandlerExceptionResolver resolver;

	private JwtAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		this.filter = new JwtAuthenticationFilter(tokenProvider, resolver);
		SecurityContextHolder.clearContext();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("토큰이 없거나 형식이 다르면")
	class NoToken {

		@Test
		@DisplayName("Authorization 헤더가 없으면 SecurityContext 는 비어있고 다음 필터로 넘어간다")
		void noAuthHeader() throws Exception {
			MockHttpServletRequest request = new MockHttpServletRequest();
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			filter.doFilter(request, response, chain);

			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(chain.getRequest()).isSameAs(request);
			verifyNoInteractions(tokenProvider);
			verifyNoInteractions(resolver);
		}

		@Test
		@DisplayName("Bearer 접두어가 없으면 토큰 없음으로 간주하고 다음 필터로 넘어간다")
		void nonBearerHeader() throws Exception {
			MockHttpServletRequest request = new MockHttpServletRequest();
			request.addHeader(HttpHeaders.AUTHORIZATION, "Basic user:pass");
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			filter.doFilter(request, response, chain);

			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(chain.getRequest()).isSameAs(request);
			verifyNoInteractions(tokenProvider);
			verifyNoInteractions(resolver);
		}
	}

	@Nested
	@DisplayName("유효한 Bearer 토큰이면")
	class ValidToken {

		@Test
		@DisplayName("SecurityContext 에 AuthPrincipal 과 ROLE_ 권한이 세팅되고 다음 필터로 넘어간다")
		void validBearerToken() throws Exception {
			String token = "valid-jwt";
			AuthPrincipal principal = new AuthPrincipal(42L, UserRole.USER);
			given(tokenProvider.parseAccessToken(token)).willReturn(principal);

			MockHttpServletRequest request = new MockHttpServletRequest();
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			filter.doFilter(request, response, chain);

			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			assertThat(auth).isNotNull();
			assertThat(auth.getPrincipal()).isEqualTo(principal);
			assertThat(auth.getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_USER");
			assertThat(chain.getRequest()).isSameAs(request);
			verifyNoInteractions(resolver);
		}

		@Test
		@DisplayName("ADMIN 역할이면 ROLE_ADMIN 권한이 세팅된다")
		void validAdminToken() throws Exception {
			String token = "admin-jwt";
			AuthPrincipal principal = new AuthPrincipal(1L, UserRole.ADMIN);
			given(tokenProvider.parseAccessToken(token)).willReturn(principal);

			MockHttpServletRequest request = new MockHttpServletRequest();
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			filter.doFilter(request, response, chain);

			assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_ADMIN");
		}
	}

	@Nested
	@DisplayName("토큰이 유효하지 않으면")
	class InvalidToken {

		@Test
		@DisplayName("EXPIRED_TOKEN 이면 SecurityContext 를 비우고 resolver 로 위임하며 필터체인은 진행되지 않는다")
		void expired() throws Exception {
			String token = "expired-jwt";
			ApiException expired = new ApiException(AuthErrorCode.EXPIRED_TOKEN);
			given(tokenProvider.parseAccessToken(token)).willThrow(expired);

			MockHttpServletRequest request = new MockHttpServletRequest();
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			filter.doFilter(request, response, chain);

			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			verify(resolver).resolveException(request, response, null, expired);
			assertThat(chain.getRequest()).as("resolver 위임 후 filterChain 은 호출되지 않아야 함").isNull();
		}

		@Test
		@DisplayName("INVALID_TOKEN 이면 SecurityContext 를 비우고 resolver 로 위임한다")
		void invalid() throws Exception {
			String token = "forged-jwt";
			ApiException invalid = new ApiException(AuthErrorCode.INVALID_TOKEN);
			given(tokenProvider.parseAccessToken(token)).willThrow(invalid);

			MockHttpServletRequest request = new MockHttpServletRequest();
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			filter.doFilter(request, response, chain);

			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			verify(resolver).resolveException(request, response, null, invalid);
			assertThat(chain.getRequest()).isNull();
		}
	}
}
