package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
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
	private MockRestServiceServer highlightServer;
	private AiClient aiClient;

	@TempDir
	private Path tempDir;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		// 선분석 전용 RestClient(MSG-351 D-5)는 별도 builder 로 조립되므로 서버도 따로 바인딩한다.
		RestClient.Builder highlightBuilder = RestClient.builder();
		highlightServer = MockRestServiceServer.bindTo(highlightBuilder).build();
		// AiClient 는 블러 플래그와 무관한 빈이라 blurEnabled=false 로 둔다 (MSG-456)
		AiProperties properties = new AiProperties(true, false, BASE_URL, Duration.ofMinutes(30), 30000L);
		aiClient = new AiClient(builder, highlightBuilder, properties);
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
	void 프리체크_탈락_응답이면_passed_false와_reason_원문을_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andRespond(withSuccess(
				"{\"job_id\":\"job-1\",\"status\":\"DONE\",\"highlights\":[],"
					+ "\"precheck\":{\"passed\":false,\"reason\":\"too_dark: std 3.18 < 10.0\"}}",
				MediaType.APPLICATION_JSON));

		AiJobResult result = aiClient.poll("job-1");

		assertThat(result.status()).isEqualTo(AiJobStatus.DONE);
		assertThat(result.precheck().passed()).isFalse();
		// reason 은 원문 그대로 — 콜론 뒤 진단 수치 파싱은 소비 지점(폴러) 몫 (MSG-284 계약)
		assertThat(result.precheck().reason()).isEqualTo("too_dark: std 3.18 < 10.0");
	}

	@Test
	void 프리체크_통과_응답이면_passed_true_reason_null로_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andRespond(withSuccess(
				"{\"job_id\":\"job-1\",\"status\":\"DONE\",\"highlights\":[[0.0,3.33]],"
					+ "\"precheck\":{\"passed\":true,\"reason\":null}}",
				MediaType.APPLICATION_JSON));

		AiJobResult result = aiClient.poll("job-1");

		assertThat(result.precheck().passed()).isTrue();
		assertThat(result.precheck().reason()).isNull();
	}

	@Test
	void precheck_필드가_없으면_null로_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andRespond(withSuccess(
				"{\"job_id\":\"job-1\",\"status\":\"PROCESSING\"}", MediaType.APPLICATION_JSON));

		AiJobResult result = aiClient.poll("job-1");

		assertThat(result.precheck()).isNull();   // 구버전 응답 = 판정 안 함 (FR-3)
	}

	@Test
	void precheck가_null이면_판정_전으로_보고_null로_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andRespond(withSuccess(
				"{\"job_id\":\"job-1\",\"status\":\"PROCESSING\",\"precheck\":null}", MediaType.APPLICATION_JSON));

		AiJobResult result = aiClient.poll("job-1");

		assertThat(result.precheck()).isNull();
	}

	@Test
	void precheck의_passed가_boolean이_아니면_null로_매핑한다() {
		server.expect(requestTo(BASE_URL + "/jobs/job-1"))
			.andRespond(withSuccess(
				"{\"job_id\":\"job-1\",\"status\":\"DONE\",\"precheck\":{\"reason\":\"x\"}}",
				MediaType.APPLICATION_JSON));

		AiJobResult result = aiClient.poll("job-1");

		// asBoolean 기본값 false 로 탈락 오판하면 정상 영상이 즉시 실패한다 — 판정 불능은 기존 경로로 (오탐 0 방향)
		assertThat(result.precheck()).isNull();
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

	@Test
	void 선분석은_highlights_구간_배열을_파싱해_반환한다() throws Exception {
		highlightServer.expect(requestTo(BASE_URL + "/highlights"))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("{\"highlights\":[[0.0,5.12],[10.0,16.4]]}", MediaType.APPLICATION_JSON));

		List<List<Double>> highlights = aiClient.analyzeHighlights(sourceFile());

		assertThat(highlights).containsExactly(List.of(0.0, 5.12), List.of(10.0, 16.4));
		highlightServer.verify();
	}

	@Test
	void 선분석_highlights가_배열이_아니면_업스트림_예외로_매핑한다() throws Exception {
		// 비배열 노드를 그대로 순회하면 예외가 아니라 빈 배열(= "추천 없음" 유효 응답)로 조용히 성공한다 (P2-1)
		highlightServer.expect(requestTo(BASE_URL + "/highlights"))
			.andRespond(withSuccess("{\"highlights\":\"garbage\"}", MediaType.APPLICATION_JSON));

		Path source = sourceFile();
		assertThatThrownBy(() -> aiClient.analyzeHighlights(source))
			.isInstanceOf(AiClient.HighlightUpstreamException.class);
	}

	@Test
	void 선분석_구간이_숫자_2개_배열이_아니면_업스트림_예외로_매핑한다() throws Exception {
		List<String> malformedBodies = List.of(
			"{\"highlights\":[[0.0]]}",              // 원소 1개짜리 구간
			"{\"highlights\":[[\"a\",\"b\"]]}",      // 숫자가 아닌 원소
			"{\"highlights\":[{\"start\":0.0}]}");   // 배열이 아닌 구간
		// MockRestServiceServer 는 첫 요청 이후 expect 추가를 금지한다 — 기대 응답을 전부 먼저 등록한다.
		for (String body : malformedBodies) {
			highlightServer.expect(requestTo(BASE_URL + "/highlights"))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		}

		Path source = sourceFile();
		for (String body : malformedBodies) {
			assertThatThrownBy(() -> aiClient.analyzeHighlights(source))
				.as("malformed body: " + body)
				.isInstanceOf(AiClient.HighlightUpstreamException.class);
		}
	}

	@Test
	void 선분석_422는_원본_불량_예외로_매핑한다() throws Exception {
		highlightServer.expect(requestTo(BASE_URL + "/highlights"))
			.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));

		Path source = sourceFile();
		assertThatThrownBy(() -> aiClient.analyzeHighlights(source))
			.isInstanceOf(AiClient.HighlightSourceRejectedException.class);
	}

	@Test
	void 선분석_타임아웃은_업스트림_예외로_매핑한다() throws Exception {
		highlightServer.expect(requestTo(BASE_URL + "/highlights"))
			.andRespond(withException(new SocketTimeoutException("read timeout")));

		Path source = sourceFile();
		assertThatThrownBy(() -> aiClient.analyzeHighlights(source))
			.isInstanceOf(AiClient.HighlightUpstreamException.class);
	}

	@Test
	void 선분석_전용_팩토리는_JDK_HttpClient_기반이고_교환_전체_시한이_120초다() {
		// SimpleClientHttpRequestFactory(HttpURLConnection)의 read timeout 은 요청 본문 쓰기를 안 묶는다 (P1-B).
		// JdkClientHttpRequestFactory 는 readTimeout 을 HttpRequest.timeout() 으로 걸어 2GiB 본문 전송까지
		// 교환 전체(전송+처리+응답)의 단일 시한이 된다.
		ClientHttpRequestFactory factory = AiClient.highlightRequestFactory();

		assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
		assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(Duration.ofSeconds(120));
		HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
		assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(5));
	}

	/** FileSystemResource 전송(D-5)이라 실존 파일이 필요하다 — 내용은 계약과 무관한 더미. */
	private Path sourceFile() throws Exception {
		return Files.writeString(tempDir.resolve("source.mp4"), "dummy-video-bytes");
	}
}
