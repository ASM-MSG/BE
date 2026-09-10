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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 애플 토큰 엔드포인트 클라이언트 (MSG-594). MockRestServiceServer 로 요청 형태·실패 매핑을 보고, 클라이언트
 * 비밀은 테스트용 EC 키쌍의 공개키로 파싱해 클레임을 단언한다. 시각은 고정 Clock — exp 가 iat + 5분인지 본다.
 */
@DisplayName("AppleTokenClient — 인가 코드 교환·토큰 취소")
class AppleTokenClientTest {

	private static final String TOKEN_URL = "https://appleid.test/auth/token";
	private static final String REVOKE_URL = "https://appleid.test/auth/revoke";
	private static final String BUNDLE_ID = "kr.fillmap.app";
	private static final String TEAM_ID = "TEAM123456";
	private static final String KEY_ID = "KEY1234567";
	private static final String CODE = "test-authorization-code";
	private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private static final KeyPair KEY_PAIR = generateKeyPair();

	private MockRestServiceServer server;
	private RestClient.Builder builder;
	private AppleTokenClient client;

	@BeforeEach
	void setUp() {
		builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = clientWithSigningKey(Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded()));
	}

	private AppleTokenClient clientWithSigningKey(String signingKey) {
		AppleOidcProperties properties = new AppleOidcProperties("https://appleid.test",
			"https://appleid.test/auth/keys", TOKEN_URL, REVOKE_URL, BUNDLE_ID, TEAM_ID, KEY_ID, signingKey, null);
		return new AppleTokenClient(builder.build(), properties, CLOCK);
	}

	private static KeyPair generateKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			return generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	// 검증: FR-AUTH-12, AC-594-08
	@Test
	void 교환_요청은_폼_인코딩으로_grant_type_client_id_client_secret_code를_보낸다() {
		// redirect_uri 는 없다 — 네이티브 흐름. client_secret 은 호출마다 서명하는 JWT 라 존재만 본다
		server.expect(requestTo(TOKEN_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().formDataContains(
				Map.of("grant_type", "authorization_code", "client_id", BUNDLE_ID, "code", CODE)))
			.andExpect(formField("client_secret", secret -> assertThat(secret).isNotBlank()))
			.andExpect(formFieldAbsent("redirect_uri"))
			.andRespond(withSuccess(tokenResponse("apple-refresh", "apple-id-token"), MediaType.APPLICATION_JSON));

		client.exchange(CODE);

		server.verify();
	}

	// 검증: FR-AUTH-12, AC-594-11
	@Test
	void 클라이언트_비밀은_ES256으로_서명되고_iss_sub_aud_kid_5분_만료를_갖는다() {
		server.expect(requestTo(TOKEN_URL))
			.andExpect(formField("client_secret", secret -> {
				Jws<Claims> jws = Jwts.parser().verifyWith((PublicKey)KEY_PAIR.getPublic())
					.clock(() -> Date.from(NOW)).build()
					.parseSignedClaims(secret);
				assertThat(jws.getHeader().getAlgorithm()).isEqualTo("ES256");
				assertThat(jws.getHeader().getKeyId()).isEqualTo(KEY_ID);
				assertThat(jws.getPayload().getIssuer()).isEqualTo(TEAM_ID);
				assertThat(jws.getPayload().getSubject()).isEqualTo(BUNDLE_ID);
				assertThat(jws.getPayload().getAudience()).containsExactly("https://appleid.apple.com");
				assertThat(jws.getPayload().getIssuedAt().toInstant()).isEqualTo(NOW);
				assertThat(jws.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(300));
			}))
			.andRespond(withSuccess(tokenResponse("apple-refresh", "apple-id-token"), MediaType.APPLICATION_JSON));

		client.exchange(CODE);

		server.verify();
	}

	// 검증: FR-AUTH-12, AC-594-08, AC-594-15
	@Test
	void 성공_응답의_refresh_token과_id_token을_함께_반환한다() {
		// access_token 은 소비하지 않는다 — refresh_token 은 탈퇴 취소용, id_token 은 sub 결속용
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withSuccess("""
				{ "access_token": "apple-access", "token_type": "Bearer", "expires_in": 3600,
					"refresh_token": "apple-refresh", "id_token": "apple-id-token" }
				""", MediaType.APPLICATION_JSON));

		AppleTokenClient.Exchanged exchanged = client.exchange(CODE);

		assertThat(exchanged.refreshToken()).isEqualTo("apple-refresh");
		assertThat(exchanged.idToken()).isEqualTo("apple-id-token");
	}

	// 검증: FR-AUTH-12, AC-594-09
	@Test
	void 애플이_invalid_grant를_주면_2423이다() {
		// 만료(5분)·재사용·다른 앱의 코드 — 사용자가 시트를 다시 열면 풀리는 실패
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{ \"error\": \"invalid_grant\" }"));

		assertExchangeFails(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
	}

	// 검증: FR-AUTH-12, AC-594-09
	@Test
	void invalid_grant가_아닌_4xx는_2502이다() {
		// invalid_client 는 우리 client_secret(팀 ID·키) 문제라 사용자 재시도로 안 풀린다 — 운영 장애로 분류
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{ \"error\": \"invalid_client\" }"));

		assertExchangeFails(AuthErrorCode.OAUTH_PROVIDER_ERROR);
	}

	// 검증: FR-AUTH-12, AC-594-09
	@Test
	void 애플이_5xx를_주거나_IO_예외가_나면_2502이다() {
		server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		server.expect(requestTo(TOKEN_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

		assertExchangeFails(AuthErrorCode.OAUTH_PROVIDER_ERROR);
		assertExchangeFails(AuthErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	// 검증: FR-AUTH-12, AC-594-09
	@Test
	void 응답에_refresh_token이나_id_token이_없으면_2502이다() {
		// 카카오의 "id_token 없음 → 2423"과 다르다 — 그쪽은 FE scope 누락, 여기는 우리가 통제 못 하는 애플 응답 이상
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withSuccess("{ \"access_token\": \"a\", \"id_token\": \"apple-id-token\" }",
				MediaType.APPLICATION_JSON));
		server.expect(requestTo(TOKEN_URL))
			.andRespond(withSuccess("{ \"access_token\": \"a\", \"refresh_token\": \"apple-refresh\" }",
				MediaType.APPLICATION_JSON));

		assertExchangeFails(AuthErrorCode.OAUTH_PROVIDER_ERROR);
		assertExchangeFails(AuthErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	// 검증: FR-AUTH-12, AC-594-10
	@Test
	void 취소_요청은_token과_token_type_hint를_보낸다() {
		server.expect(requestTo(REVOKE_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().formDataContains(
				Map.of("client_id", BUNDLE_ID, "token", "apple-refresh", "token_type_hint", "refresh_token")))
			.andExpect(formField("client_secret", secret -> assertThat(secret).isNotBlank()))
			.andRespond(withSuccess());

		client.revoke("apple-refresh");

		server.verify();
	}

	// 검증: FR-AUTH-12, D-10
	@Test
	void 서명_키가_비어_있으면_애플을_부르지_않고_2502이다() {
		AppleTokenClient withoutKey = clientWithSigningKey(" ");

		assertThatThrownBy(() -> withoutKey.exchange(CODE))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_ERROR));
		server.verify();	// 기대 요청 0건 — 애플 왕복이 없었다
	}

	private static String tokenResponse(String refreshToken, String idToken) {
		return "{\"token_type\":\"Bearer\",\"refresh_token\":\"" + refreshToken
			+ "\",\"id_token\":\"" + idToken + "\"}";
	}

	private void assertExchangeFails(ErrorCodeIfs expected) {
		assertThatThrownBy(() -> client.exchange(CODE))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	private static RequestMatcher formField(String name, Consumer<String> assertion) {
		return request -> {
			String value = formFields(request).get(name);
			assertThat(value).as("form field %s", name).isNotNull();
			assertion.accept(value);
		};
	}

	private static RequestMatcher formFieldAbsent(String name) {
		return request -> assertThat(formFields(request)).doesNotContainKey(name);
	}

	private static Map<String, String> formFields(ClientHttpRequest request) {
		Map<String, String> fields = new HashMap<>();
		for (String pair : ((MockClientHttpRequest)request).getBodyAsString().split("&")) {
			int eq = pair.indexOf('=');
			fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
				URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return fields;
	}
}
