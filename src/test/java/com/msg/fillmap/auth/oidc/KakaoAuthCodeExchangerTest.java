package com.msg.fillmap.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 카카오 토큰 엔드포인트 교환 어댑터를 MockRestServiceServer(spring-test, 신규 의존성 없음)로 스텁해 요청 형태와
 * 실패 매핑만 검증한다 (MSG-345, KakaoLocalClientTest 선례 구도). 실 카카오 왕복은 DoD 수동 확인이 겸한다.
 * builder 에 bind 후 KakaoTokenClientConfig 와 같은 baseUrl 을 얹어 빌드한 RestClient 로 어댑터를 만든다.
 */
@DisplayName("KakaoAuthCodeExchanger — 카카오 인가 코드 → ID Token 교환")
class KakaoAuthCodeExchangerTest {

	private static final String TOKEN_URL = "https://kauth.test/oauth/token";
	private static final String CLIENT_ID = "test-rest-key";
	private static final String CODE = "test-authorization-code";
	private static final String REDIRECT_URI = "http://localhost:5173/oauth/kakao/callback";
	private static final String NONCE = "f47ac10b58cc4372a5670e02b2c3d479";
	private static final String ID_TOKEN = idTokenWithClaims("{\"sub\":\"kakao-1\",\"nonce\":\"" + NONCE + "\"}");

	private MockRestServiceServer server;
	private KakaoAuthCodeExchanger exchanger;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		KakaoOidcProperties properties = new KakaoOidcProperties("https://kauth.test",
			"https://kauth.test/.well-known/jwks.json", CLIENT_ID, TOKEN_URL, "https://kauth.test/oauth/authorize",
			true);
		exchanger = new KakaoAuthCodeExchanger(builder.baseUrl(TOKEN_URL).build(), properties, new ObjectMapper());
	}

	/**
	 * payload 에 주어진 클레임을 담은 JWT 모양의 문자열. 서명은 검증 대상이 아니라(어댑터는 payload 만 읽고
	 * 서명·issuer·aud 는 이후 kakaoJwtDecoder 몫) 자리만 채운다.
	 */
	private static String idTokenWithClaims(String claimsJson) {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
			+ "." + encoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8))
			+ ".fake-signature";
	}

	@Test
	void 교환_요청은_폼_인코딩으로_grant_type_client_id_redirect_uri_code를_보낸다() {
		// nonce 는 폼에 없다 — 카카오로 보내는 값이 아니라 돌아온 ID Token 과 맞춰 볼 값이다(보안 절)
		MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
		expectedForm.add("grant_type", "authorization_code");
		expectedForm.add("client_id", CLIENT_ID);
		expectedForm.add("redirect_uri", REDIRECT_URI);
		expectedForm.add("code", CODE);

		server.expect(requestTo(TOKEN_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().formData(expectedForm))
			.andRespond(withSuccess(tokenResponse(ID_TOKEN), MediaType.APPLICATION_JSON));

		exchanger.exchange(CODE, REDIRECT_URI, NONCE);

		server.verify();
	}

	@Test
	void 성공_응답의_id_token을_반환한다() {
		// access_token 등 나머지 필드는 소비하지 않는다 — 사용자 정보는 ID Token 클레임으로 충분하다
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withSuccess("""
				{ "token_type": "bearer", "access_token": "kakao-access", "expires_in": 21599,
					"refresh_token": "kakao-refresh", "id_token": "%s", "scope": "openid" }
				""".formatted(ID_TOKEN), MediaType.APPLICATION_JSON));

		assertThat(exchanger.exchange(CODE, REDIRECT_URI, NONCE)).isEqualTo(ID_TOKEN);
	}

	@Test
	void id_token의_nonce_클레임이_요청_nonce와_다르면_유효하지_않은_인가_코드_예외를_던진다() {
		// 공격자가 주입한 인가 코드로 만들어진 토큰 — FE 의 state 대조가 뚫려도 서버가 여기서 잡는다(FR-8)
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withSuccess(
				tokenResponse(idTokenWithClaims("{\"sub\":\"kakao-1\",\"nonce\":\"another-login-attempt\"}")),
				MediaType.APPLICATION_JSON));

		assertFails(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
	}

	@Test
	void id_token에_nonce_클레임이_없으면_유효하지_않은_인가_코드_예외를_던진다() {
		// 클레임 부재도 불일치와 같은 401 — 대조를 못 했는데 통과시키면 방어가 없는 것과 같다
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withSuccess(tokenResponse(idTokenWithClaims("{\"sub\":\"kakao-1\"}")),
				MediaType.APPLICATION_JSON));

		assertFails(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
	}

	@Test
	void 카카오가_invalid_grant를_주면_유효하지_않은_인가_코드_예외를_던진다() {
		// 만료·재사용·redirect URI 불일치가 전부 여기로 온다. KOE 코드는 로그 전용이라 응답 메시지엔 새지 않는다
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{ "error": "invalid_grant", "error_code": "KOE320",
						"error_description": "authorization code not found for code=..." }
					"""));

		assertFails(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
	}

	@Test
	void invalid_grant가_아닌_4xx는_제공자_오류_예외를_던진다() {
		// 레이트 리밋(KOE237)·앱 키 오류는 사용자가 다시 로그인해도 안 풀리는 운영 장애다 — 2423 으로 뭉개면
		// 사용자에게는 "코드가 무효"로 보이고 모니터링에도 안 걸린다
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{ "error": "too_many_requests", "error_code": "KOE237",
						"error_description": "restricted by rate limit" }
					"""));

		assertFails(AuthErrorCode.OAUTH_PROVIDER_ERROR);
	}

	@Test
	void 응답에_id_token이_없으면_유효하지_않은_인가_코드_예외를_던진다() {
		// 인가 요청에 scope=openid 가 빠지면 2xx 인데 id_token 이 없다 — 무효 코드와 같은 401 로 수렴시킨다(FR-6)
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withSuccess("{\"token_type\":\"bearer\",\"access_token\":\"kakao-access\"}",
				MediaType.APPLICATION_JSON));

		assertFails(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
	}

	@Test
	void 카카오가_5xx를_주면_제공자_오류_예외를_던진다() {
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertFails(AuthErrorCode.OAUTH_PROVIDER_ERROR);
	}

	@Test
	void IO_예외가_나면_제공자_오류_예외를_던진다() {
		// 연결 실패·읽기 타임아웃 — 사용자 잘못이 아니므로 401 로 새지 않고 502 로 수렴한다
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withException(new SocketTimeoutException("read timed out")));

		assertFails(AuthErrorCode.OAUTH_PROVIDER_ERROR);
	}

	private static String tokenResponse(String idToken) {
		return "{\"token_type\":\"bearer\",\"id_token\":\"" + idToken + "\"}";
	}

	private void assertFails(ErrorCodeIfs expected) {
		assertThatThrownBy(() -> exchanger.exchange(CODE, REDIRECT_URI, NONCE))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}
}
