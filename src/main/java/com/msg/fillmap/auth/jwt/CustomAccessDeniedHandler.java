package com.msg.fillmap.auth.jwt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.response.ApiResponseDto;
import com.msg.fillmap.response.ErrorCode;

/**
 * 인가 실패(인증은 됐지만 권한 부족) 응답을 공통 형식으로 맞춘다 (MSG-195 §D7).
 * 등록하지 않으면 스프링 시큐리티 기본 AccessDeniedHandlerImpl 이 sendError(403) 으로 /error 에
 * 포워드해 Boot 기본 에러 JSON(timestamp·status·error·path)이 나가고 developCode 가 없다.
 *
 * <p>CustomAuthenticationEntryPoint 와 같은 이유로 @Component 를 걸지 않고 SecurityConfig 가
 * @Bean 으로 명시 등록한다. 권한 부족은 도메인 사정과 무관하므로 공통 ErrorCode.FORBIDDEN(403)이다.
 */
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException accessDeniedException
	) throws IOException {
		ErrorCode errorCode = ErrorCode.FORBIDDEN;

		response.setStatus(errorCode.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		ApiResponseDto<Object> body = ApiResponseDto.builder()
			.developCode(errorCode.getErrorCode())
			.message(errorCode.getMessage())
			.build();

		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}
