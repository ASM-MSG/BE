package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.msg.fillmap.video.config.AiProperties;
import com.msg.fillmap.video.service.AiClient.AiJobResult;
import com.msg.fillmap.video.service.AiClient.AiJobStatus;

/**
 * AI 서버 어댑터를 MockRestServiceServer(spring-test, 신규 의존성 없음)로 스텁해 계약 매핑만 검증한다.
 * 실 HTTP·FastAPI 없이 RestClient 의 요청/응답을 가로챈다.
 */
@DisplayName("AiClient — AI 서버 계약 매핑")
class AiClientTest {

	private static final String BASE_URL = "http://ai.test";

	private MockRestServiceServer server;
	private AiClient aiClient;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		AiProperties properties = new AiProperties(true, BASE_URL, Duration.ofMinutes(30), 30000L);
		aiClient = new AiClient(builder, properties);
	}

	@Test
	void 제출하면_202의_job_id를_파싱해_반환한다() {
		server.expect(requestTo(BASE_URL + "/jobs"))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.ACCEPTED)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"job_id\":\"job-1\",\"status\":\"QUEUED\"}"));

		String jobId = aiClient.submit("encoded-bytes".getBytes());

		assertThat(jobId).isEqualTo("job-1");
		server.verify();
	}

	@Test
	void DONE_응답이면_상태와_하이라이트_구간을_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				"{\"job_id\":\"job-1\",\"status\":\"DONE\",\"highlights\":[[0.0,3.33]]}", MediaType.APPLICATION_JSON));

		AiJobResult result = aiClient.poll("job-1");

		assertThat(result.status()).isEqualTo(AiJobStatus.DONE);
		assertThat(result.notFound()).isFalse();
		assertThat(result.highlights()).containsExactly(List.of(0.0, 3.33));
	}

	@Test
	void FAILED_응답이면_실패로_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andRespond(withSuccess(
				"{\"job_id\":\"job-1\",\"status\":\"FAILED\",\"error\":\"boom\"}", MediaType.APPLICATION_JSON));

		AiJobResult result = aiClient.poll("job-1");

		assertThat(result.status()).isEqualTo(AiJobStatus.FAILED);
		assertThat(result.notFound()).isFalse();
	}

	@Test
	void status가_해석불가면_UNKNOWN으로_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andRespond(withSuccess(
				"{\"job_id\":\"job-1\",\"status\":\"WEIRD_GARBAGE\"}", MediaType.APPLICATION_JSON));

		AiJobResult result = aiClient.poll("job-1");

		assertThat(result.status()).isEqualTo(AiJobStatus.UNKNOWN);   // valueOf 예외가 아니라 안전 매핑 (P2-b)
		assertThat(result.notFound()).isFalse();
	}

	@Test
	void 잡이_404면_유실로_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND));

		AiJobResult result = aiClient.poll("job-1");

		assertThat(result.notFound()).isTrue();
		assertThat(result.status()).isNull();
	}

	@Test
	void 완료본_다운로드는_블러본_바이트를_반환한다() {
		byte[] blurred = {1, 2, 3, 4};
		server.expect(requestTo(BASE_URL + "/jobs/job-1/video"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(blurred, MediaType.valueOf("video/mp4")));

		assertThat(aiClient.downloadBlurred("job-1")).isEqualTo(blurred);
		server.verify();
	}

	@Test
	void 완료본이_아직_없으면_409를_미완료로_처리해_null을_반환한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1/video"))
			.andRespond(withStatus(HttpStatus.CONFLICT));

		assertThat(aiClient.downloadBlurred("job-1")).isNull();
	}
}
