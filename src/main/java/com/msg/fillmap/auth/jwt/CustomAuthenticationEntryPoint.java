package com.msg.fillmap.auth.jwt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.response.ApiResponseDto;

@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException
	) throws IOException {
		AuthErrorCode errorCode = AuthErrorCode.UNAUTHENTICATED;

		response.setStatus(errorCode.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		ApiResponseDto<Object> body = ApiResponseDto.builder()
			.developCode(errorCode.getErrorCode())
			.httpStatus(errorCode.getHttpStatus())
			.message(errorCode.getMessage())
			.build();

		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}
