package com.msg.fillmap.auth.oidc;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.jsonwebtoken.Jwts;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 애플 토큰 엔드포인트 클라이언트 (MSG-594). 첫 로그인의 인가 코드를 리프레시 토큰으로 바꾸고(exchange), 탈퇴 때
 * 그 토큰을 취소한다(revoke). 두 호출의 client_secret 은 호출 시점에 ES256 으로 서명한 5분짜리 JWT 다(D-10).
 *
 * 호출·파싱·실패 매핑만 한다 — 교환 응답의 id_token 은 검증하지 않는다. 검증과 sub 대조는
 * AppleOidcIdTokenVerifier.verifySubject 와 OidcLoginService 몫이다(KakaoAuthCodeExchanger 선례).
 * 로그 금지 항목: 인가 코드·리프레시 토큰(암호문 포함)·클라이언트 비밀·ID 토큰 원문. 애플 error 값과 상태만 남긴다.
 */
@Slf4j
@Component
public class AppleTokenClient {

	private static final String GRANT_TYPE = "authorization_code";
	// 애플 에러 응답의 error 값 — 인가 코드 자체가 무효(만료 5분·재사용·다른 앱의 코드)
	private static final String INVALID_GRANT = "invalid_grant";
	private static final String APPLE_AUDIENCE = "https://appleid.apple.com";
	// 애플 허용 상한은 6개월이지만 호출 직전에 만들어 바로 쓰는 값이라 길게 둘 이유가 없다. 캐시하지 않는다.
	private static final Duration CLIENT_SECRET_TTL = Duration.ofMinutes(5);

	// 완성 RestClient 빈이 셋이라 by-type 주입이 모호하다 — 필드명(=생성자 파라미터명)을 빈 이름과 맞춰 by-name 으로
	// 해소한다(KakaoAuthCodeExchanger 선례). 이름을 바꾸면 기동이 깨지므로 리네임 금지.
	private final RestClient appleTokenRestClient;
	private final AppleOidcProperties properties;
	private final Clock clock;
	// 첫 호출 때 한 번 파싱해 보관한다 — 기동 시 파싱하면 키 없는 로컬 개발자가 부팅을 못 한다(D-10)
	private volatile PrivateKey signingKey;

	/** 교환 결과. refreshToken 은 암호화해 보관하고 idToken 은 요청 ID 토큰과 같은 애플 계정인지 결속하는 데 쓴다. */
	public record Exchanged(String refreshToken, String idToken) {
	}

	/** 프로덕션 생성자 — clock 을 Clock.systemUTC() 로 고정한다(BadgeAwardServiceImpl 선례). 전체 생성자는 테스트용. */
	@Autowired
	public AppleTokenClient(RestClient appleTokenRestClient, AppleOidcProperties properties) {
		this(appleTokenRestClient, properties, Clock.systemUTC());
	}

	public AppleTokenClient(RestClient appleTokenRestClient, AppleOidcProperties properties, Clock clock) {
		this.appleTokenRestClient = appleTokenRestClient;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 인가 코드를 리프레시 토큰과 ID 토큰으로 교환한다. redirect_uri 는 보내지 않는다(네이티브 흐름).
	 * 실패 매핑: 4xx invalid_grant → 2423, 그 외 4xx·5xx·연결 실패·타임아웃·응답 필드 부재 → 2502.
	 */
	public Exchanged exchange(String authorizationCode) {
		// 클라이언트 비밀을 먼저 만든다 — 서명 키 미설정이면 애플을 부르기 전에 2502 로 끝난다(D-10)
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", GRANT_TYPE);
		form.add("client_id", properties.bundleId());
		form.add("client_secret", clientSecret());
		form.add("code", authorizationCode);

		JsonNode response;
		try {
			response = appleTokenRestClient.post()
				.uri(properties.tokenUri())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(JsonNode.class);
		} catch (RestClientResponseException e) {
			throw fromErrorResponse(e);
		} catch (RestClientException e) {
			// 연결 실패·읽기 타임아웃(ResourceAccessException)·비JSON 본문 디코딩 실패
			log.warn("애플 토큰 엔드포인트 호출 실패 — 2502 로 수렴: cause={}", e.toString());
			throw new ApiException(AuthErrorCode.OAUTH_PROVIDER_ERROR, e);
		}

		String refreshToken = response == null ? "" : response.path("refresh_token").asString("");
		String idToken = response == null ? "" : response.path("id_token").asString("");
		if (refreshToken.isBlank() || idToken.isBlank()) {
			// 우리가 통제할 수 없는 애플 응답 이상 — 카카오의 "id_token 없음 → 2423"(FE scope 누락)과 다른 이유
			log.warn("애플 토큰 응답에 refresh_token 또는 id_token 이 없습니다 — 2502 로 수렴: refreshToken 부재={}, idToken 부재={}",
				refreshToken.isBlank(), idToken.isBlank());
			throw new ApiException(AuthErrorCode.OAUTH_PROVIDER_ERROR);
		}
		return new Exchanged(refreshToken, idToken);
	}

	/**
	 * 리프레시 토큰 취소. 성공은 본문 없는 200 이다. 실패는 매핑하지 않고 그대로 전파한다 — 호출자
	 * (UserServiceImpl 의 커밋 후 훅)가 best-effort 로 감싼다(D-9).
	 */
	public void revoke(String refreshToken) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", properties.bundleId());
		form.add("client_secret", clientSecret());
		form.add("token", refreshToken);
		form.add("token_type_hint", "refresh_token");

		appleTokenRestClient.post()
			.uri(properties.revokeUri())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			.toBodilessEntity();
	}

	/** 애플 문서 기준 클라이언트 비밀: ES256, kid=Key ID, iss=Team ID, sub=번들 ID, aud=애플, exp=지금+5분. */
	private String clientSecret() {
		Instant now = Instant.now(clock);
		return Jwts.builder()
			.header().keyId(properties.keyId()).and()
			.issuer(properties.teamId())
			.subject(properties.bundleId())
			.audience().add(APPLE_AUDIENCE).and()
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plus(CLIENT_SECRET_TTL)))
			.signWith(signingKey(), Jwts.SIG.ES256)
			.compact();
	}

	/**
	 * .p8 의 BEGIN/END 줄을 뺀 Base64 본문(환경변수 한 줄)을 EC PKCS#8 개인키로 파싱한다. 표준 라이브러리만 쓴다.
	 * 경합해도 같은 값을 두 번 파싱할 뿐이라 잠금은 없다.
	 */
	private PrivateKey signingKey() {
		PrivateKey key = signingKey;
		if (key != null) {
			return key;
		}
		String raw = properties.signingKey();
		if (raw == null || raw.isBlank()) {
			log.warn("애플 서명 키 미설정(oauth.apple.signing-key) — 애플 왕복 없이 2502 로 수렴");
			throw new ApiException(AuthErrorCode.OAUTH_PROVIDER_ERROR);
		}
		try {
			byte[] pkcs8 = Base64.getDecoder().decode(raw.replaceAll("\\s", ""));
			key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			log.warn("애플 서명 키 파싱 실패 — 2502 로 수렴: cause={}", e.toString());
			throw new ApiException(AuthErrorCode.OAUTH_PROVIDER_ERROR, e);
		}
		signingKey = key;
		return key;
	}

	/**
	 * 애플이 상태 코드로 거절한 경우. 사용자가 시트를 다시 열면 풀리는 실패(4xx invalid_grant)만 2423 이고,
	 * invalid_client(우리 client_secret 문제)처럼 재시도로 안 풀리는 거절과 5xx 는 운영 장애라 2502 로 분류한다.
	 */
	private ApiException fromErrorResponse(RestClientResponseException e) {
		String error = errorBody(e).path("error").asString("");
		log.warn("애플 인가 코드 교환 실패: status={}, error={}", e.getStatusCode().value(), error);
		if (e.getStatusCode().is4xxClientError() && INVALID_GRANT.equals(error)) {
			return new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE, e);
		}
		return new ApiException(AuthErrorCode.OAUTH_PROVIDER_ERROR, e);
	}

	/** 애플 에러 응답 body 의 error 값만 쓴다. 본문이 비었거나 JSON 이 아니면 MissingNode 라 "" 로 떨어진다. */
	private JsonNode errorBody(RestClientResponseException e) {
		try {
			JsonNode body = e.getResponseBodyAs(JsonNode.class);
			return body == null ? MissingNode.getInstance() : body;
		} catch (RuntimeException ignored) {
			return MissingNode.getInstance();
		}
	}
}
