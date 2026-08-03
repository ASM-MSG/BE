package com.msg.fillmap.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.global.GlobalExceptionHandler;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 공통 응답 와이어 계약(MSG-265·MSG-311): 응답 본문은 developCode·message·data 3필드만
 * 직렬화되고, HTTP 상태 코드는 응답 본문이 아니라 status line 으로만 내려간다.
 * 필드 집합은 정확 일치로 단언한다 — contains 방식은 4번째 필드가 새로 새어도 통과한다 (Codex 리뷰 반영).
 */
class ApiResponseContractTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void 성공_응답은_developCode_message_data_3필드만_직렬화된다() {
		SuccessResponse<String> response = SuccessResponse.of("데이터");

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(json.properties()).extracting(Map.Entry::getKey)
			.containsExactlyInAnyOrder("developCode", "message", "data");
		assertThat(json.get("developCode").intValue()).isEqualTo(200);
	}

	@Test
	void 에러_응답도_data_키로_직렬화되고_상태_코드는_status_line_으로_유지된다() {
		ResponseEntity<ApiResponseDto<Object>> response =
			handler.handleApiException(new ApiException(ErrorCode.NOT_FOUND));

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(json.properties()).extracting(Map.Entry::getKey)
			.containsExactlyInAnyOrder("developCode", "message", "data");
		assertThat(json.get("developCode").intValue()).isEqualTo(404);
		assertThat(json.get("data").isNull()).isTrue();
	}
}
