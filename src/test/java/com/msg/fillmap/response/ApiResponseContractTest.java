package com.msg.fillmap.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.global.GlobalExceptionHandler;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 공통 응답 와이어 계약(MSG-265): body 는 developCode·message·body 3필드만 직렬화되고,
 * HTTP 상태 코드는 body 가 아니라 status line 으로만 내려간다.
 */
class ApiResponseContractTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void 성공_응답_body는_developCode_message_body_3필드만_직렬화된다() {
		SuccessResponse<String> response = SuccessResponse.of("데이터");

		String json = objectMapper.writeValueAsString(response.getBody());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(json)
			.contains("\"developCode\":200")
			.contains("\"message\"")
			.contains("\"body\"")
			.doesNotContain("httpStatus");
	}

	@Test
	void 에러_응답도_httpStatus_없이_직렬화되고_상태_코드는_status_line_으로_유지된다() {
		ResponseEntity<ApiResponseDto<Object>> response =
			handler.handleApiException(new ApiException(ErrorCode.NOT_FOUND));

		String json = objectMapper.writeValueAsString(response.getBody());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(json)
			.contains("\"developCode\":404")
			.doesNotContain("httpStatus");
	}
}
