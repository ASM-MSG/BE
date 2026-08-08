package com.msg.fillmap.auth.oidc;

import java.util.Base64;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 웹 카카오 로그인의 인가 코드를 카카오 토큰 엔드포인트에서 OIDC ID Token 으로 바꾸는 어댑터 (MSG-345).
 * 카카오는 이 교환을 REST API 키로 서비스 서버가 하도록 요구해서, 브라우저가 못 하는 구간만 서버가 대행한다.
 *
 * webmvc 내장 RestClient 만 쓴다 — 신규 HTTP 의존성 없음. baseUrl·타임아웃은 KakaoTokenClientConfig 의
 * 완성 빈 소관이라 여기서는 호출·파싱·실패 매핑만 한다(KakaoLocalClient 선례). 반환값은 ID Token 원문이고,
 * 검증은 기존 kakaoJwtDecoder 가 그대로 한다 — 이 클래스는 검증을 대신하거나 완화하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoAuthCodeExchanger {

	private static final String GRANT_TYPE = "authorization_code";
	// 카카오 에러 응답의 error 값 — 인가 코드 자체가 무효라는 뜻(만료·재사용·redirect URI 불일치)
	private static final String INVALID_GRANT = "invalid_grant";

	// 완성 RestClient 빈이 kakaoLocalRestClient 와 둘이라 by-type 주입이 모호하다 — 필드명(=생성자 파라미터명)을
	// 빈 이름과 맞춰 by-name 으로 해소한다. 이름을 바꾸면 기동이 깨지므로 리네임 금지.
	private final RestClient kakaoTokenRestClient;
	private final KakaoOidcProperties properties;
	private final ObjectMapper objectMapper;

	/**
	 * 인가 코드를 ID Token 원문으로 교환하고 nonce 대조까지 통과시킨다. redirectUri 는 인가 요청에 썼던 값
	 * 그대로여야 하며, 콘솔 등록값과의 정확 일치는 카카오가 검증한다(서버 화이트리스트 없음 — FR-4).
	 * nonce 는 FE 가 인가 요청에 넣은 난수 그대로 — 카카오로 보내는 값이 아니라 돌아온 ID Token 과 맞춰 볼 값이다.
	 */
	public String exchange(String code, String redirectUri, String nonce) {
		JsonNode response;
		try {
			response = kakaoTokenRestClient.post()
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(formBody(code, redirectUri))
				.retrieve()
				.body(JsonNode.class);
		} catch (RestClientResponseException e) {
			throw fromErrorResponse(e, redirectUri);
		} catch (RestClientException e) {
			// 연결 실패·읽기 타임아웃(ResourceAccessException)·비JSON 본문 디코딩 실패
			throw providerError(e, redirectUri);
		}

		String idToken = response == null ? "" : response.path("id_token").asString("");
		if (idToken.isBlank()) {
			// 인가 요청에 scope=openid 가 빠지면 교환은 성공해도 id_token 이 없다 — 무효 코드와 같은 401 로 수렴(FR-6)
			log.warn("카카오 토큰 응답에 id_token 이 없습니다 — scope=openid 누락 의심, 2423 으로 수렴: redirectUri={}",
				redirectUri);
			throw new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
		}
		verifyNonce(idToken, nonce);
		return idToken;
	}

	/**
	 * ID Token 의 nonce 클레임이 이 로그인 시도의 nonce 와 같은지 본다 (PRD FR-8). 주입된 인가 코드로 만들어진
	 * 토큰을 FE 의 state 대조와 무관하게 서버가 잡는 심층 방어다.
	 *
	 * payload 만 서명 검증 없이 읽는다 — 서명·issuer·audience 는 직후 kakaoJwtDecoder 가 그대로 검증하므로
	 * 위조 토큰은 어차피 다음 단계에서 기각된다. 여기서 서명을 또 보면 검증기와 중복이다.
	 */
	private void verifyNonce(String idToken, String nonce) {
		if (!nonce.equals(nonceClaim(idToken))) {
			// 불일치 사실만 남긴다 — nonce·토큰 원문은 로그 금지 항목(비기능 보안)
			log.warn("카카오 ID Token 의 nonce 클레임이 요청 nonce 와 일치하지 않습니다 — 2423 으로 수렴");
			throw new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
		}
	}

	/** JWT payload(점으로 나뉜 2번째 세그먼트)의 nonce 클레임. 클레임 부재·형식 이상은 "" 로 떨어져 불일치가 된다. */
	private String nonceClaim(String idToken) {
		String[] segments = idToken.split("\\.");
		if (segments.length < 2) {
			return "";
		}
		try {
			return objectMapper.readTree(Base64.getUrlDecoder().decode(segments[1])).path("nonce").asString("");
		} catch (RuntimeException e) {
			// Base64 디코드 실패·비JSON payload — JWT 형태가 아니면 대조 불가라 불일치로 취급한다
			return "";
		}
	}

	private MultiValueMap<String, String> formBody(String code, String redirectUri) {
		// client_secret 은 보내지 않는다 — 콘솔의 카카오 로그인 시크릿이 OFF (PRD 8절 승인). 켜면 한 줄 추가.
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", GRANT_TYPE);
		form.add("client_id", properties.clientId());
		form.add("redirect_uri", redirectUri);
		form.add("code", code);
		return form;
	}

	/**
	 * 카카오가 상태 코드로 거절한 경우. 사용자가 다시 로그인하면 풀리는 실패(4xx 중 error=invalid_grant —
	 * 만료·재사용·redirect URI 불일치)만 2423 이고, 레이트 리밋(KOE237)·앱 설정 오류(invalid_client)처럼
	 * 재시도로 안 풀리는 거절과 5xx 는 운영 장애라 2502 로 분류해 모니터링에 걸리게 한다.
	 * 사유는 응답에 싣지 않고 로그로만 남긴다(FR-5).
	 */
	private ApiException fromErrorResponse(RestClientResponseException e, String redirectUri) {
		JsonNode body = errorBody(e);
		String error = body.path("error").asString("");
		log.warn("카카오 인가 코드 교환 실패: status={}, error={}, errorCode={}, redirectUri={}",
			e.getStatusCode().value(), error, body.path("error_code").asString(""), redirectUri);
		if (e.getStatusCode().is4xxClientError() && INVALID_GRANT.equals(error)) {
			return new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE, e);
		}
		return new ApiException(AuthErrorCode.OAUTH_PROVIDER_ERROR, e);
	}

	private ApiException providerError(Exception cause, String redirectUri) {
		log.warn("카카오 토큰 엔드포인트 호출 실패 — 2502 로 수렴: redirectUri={}, cause={}", redirectUri, cause.toString());
		return new ApiException(AuthErrorCode.OAUTH_PROVIDER_ERROR, cause);
	}

	/**
	 * 카카오 에러 응답 body. 통째로 로그에 찍지 않고 error·error_code 만 꺼내 쓰는 이유는 error_description 에
	 * 인가 코드 원문이 실려 오기 때문이다(로그 금지 항목 — 비기능 보안).
	 * 본문이 비었거나 JSON 이 아니면 MissingNode 라 path().asString("") 이 빈 문자열로 떨어진다.
	 */
	private JsonNode errorBody(RestClientResponseException e) {
		try {
			JsonNode body = e.getResponseBodyAs(JsonNode.class);
			return body == null ? MissingNode.getInstance() : body;
		} catch (RuntimeException ignored) {
			return MissingNode.getInstance();
		}
	}
}
