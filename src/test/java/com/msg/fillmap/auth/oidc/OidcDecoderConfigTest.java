package com.msg.fillmap.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("OidcDecoderConfig audience 검증")
class OidcDecoderConfigTest {

	private static final String REST_API_KEY = "rest-api-key";
	private static final String NATIVE_APP_KEY = "native-app-key";

	private final OAuth2TokenValidator<Jwt> validator =
		OidcDecoderConfig.audienceValidator(Set.of(REST_API_KEY, NATIVE_APP_KEY));

	@Test
	void 웹_경로의_REST_API_키_aud_토큰은_통과한다() {
		assertThat(validator.validate(jwtWithAudience(REST_API_KEY)).hasErrors()).isFalse();
	}

	@Test
	void 앱_경로의_네이티브_앱_키_aud_토큰은_통과한다() {
		// 검증: MSG-452 — 네이티브 SDK 가 받은 ID Token 의 aud 는 네이티브 앱 키다
		assertThat(validator.validate(jwtWithAudience(NATIVE_APP_KEY)).hasErrors()).isFalse();
	}

	@Test
	void 허용_목록에_없는_aud_토큰은_거부된다() {
		assertThat(validator.validate(jwtWithAudience("other-app-key")).hasErrors()).isTrue();
	}

	private Jwt jwtWithAudience(String audience) {
		return Jwt.withTokenValue("id-token")
			.header("alg", "RS256")
			.audience(List.of(audience))
			.claim("sub", "kakao-12345")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60))
			.build();
	}
}
