package com.msg.fillmap.global;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

import com.msg.fillmap.response.ApiResponseDto;

/**
 * catch-all 오류 로그 검증 (MSG-342) — 핸들러 직접 생성(ApiResponseContractTest 선례) +
 * MockHttpServletRequest 순수 단위 테스트. 로그는 ListAppender 를 테스트마다 붙였다 뗀다 (MSG-330 선례).
 */
@DisplayName("GlobalExceptionHandler catch-all 오류 로그")
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	private ListAppender<ILoggingEvent> logAppender;

	@BeforeEach
	void attachLogAppender() {
		logAppender = new ListAppender<>();
		logAppender.start();
		handlerLogger().addAppender(logAppender);
	}

	@AfterEach
	void detachLogAppender() {
		handlerLogger().detachAppender(logAppender);
	}

	@Test
	void 처리되지_않은_예외는_ERROR_로그를_정확히_한_번_남긴다() {
		RuntimeException exception = new RuntimeException("DB 연결 끊김");

		handler.handleException(exception, request());

		assertThat(logAppender.list).hasSize(1);
		ILoggingEvent event = logAppender.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.ERROR);
		// throwable 이 던진 예외 그대로여야 스택 추적이 원인 체인까지 보존된다 (D-1)
		assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable()).isSameAs(exception);
	}

	@Test
	void 오류_로그에_HTTP_방법과_경로와_developCode가_담긴다() {
		handler.handleException(new RuntimeException("DB 연결 끊김"), request());

		assertThat(logAppender.list.get(0).getFormattedMessage())
				.contains("POST")
				.contains("/api/videos")
				.contains("500");
	}

	@Test
	void 오류_로그에_쿼리_문자열은_담기지_않는다() {
		MockHttpServletRequest request = request();
		request.setQueryString("query=강남 맛집");

		handler.handleException(new RuntimeException("DB 연결 끊김"), request);

		// getRequestURI 만 쓰는 D-1 규칙 — 쿼리 문자열이 실리면 검색어 원문 제거(D-2)가 이 로그로 다시 뚫린다
		assertThat(logAppender.list.get(0).getFormattedMessage()).doesNotContain("강남");
	}

	@Test
	void 예외가_와도_500_응답의_developCode와_메시지는_그대로다() {
		ResponseEntity<ApiResponseDto<Object>> response =
				handler.handleException(new RuntimeException("DB 연결 끊김"), request());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody().getDevelopCode()).isEqualTo(500);
		assertThat(response.getBody().getMessage()).isEqualTo("서버 내부 오류");
		assertThat(response.getBody().getData()).isNull();
	}

	private static MockHttpServletRequest request() {
		return new MockHttpServletRequest("POST", "/api/videos");
	}

	private static Logger handlerLogger() {
		return (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
	}
}
