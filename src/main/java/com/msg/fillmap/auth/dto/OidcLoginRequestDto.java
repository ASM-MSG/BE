package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * 소셜(OIDC) 로그인 요청. idToken 외 세 필드는 애플 전용 선택값이다 (MSG-594) — 같은 DTO 를 카카오 요청이 쓰고
 * 카카오는 이 필드가 없어 @NotBlank 로 묶지 않는다. 제공자별 필수 여부는 서비스가 판정한다(카카오 요청에 실려 와도 무시).
 */
@Schema(description = "소셜(OIDC) 로그인 요청")
public record OidcLoginRequestDto(
	@Schema(description = "소셜 제공자(카카오·애플)에서 발급받은 OIDC ID Token", example = "eyJraWQiOiI...")
	@NotBlank(message = "idToken은 필수 항목입니다")
	String idToken,

	@Schema(description = "앱이 요청마다 만든 nonce 원문 (APPLE 필수, 카카오는 무시). 앱은 이 값의 SHA-256 16진 소문자를 "
		+ "애플 로그인 시트에 넘기고 원문을 서버에 보낸다 — 원문을 시트에 넘기면 대조가 항상 실패한다(2421)",
		example = "3f9a1c...")
	String nonce,

	@Schema(description = "애플 authorizationCode (APPLE 필수). 첫 로그인인지 가리지 말고 매번 보낸다 — 서버가 계정이 "
		+ "없을 때만 교환한다. 5분 안에 1회만 교환 가능", example = "c8a3b1...")
	String authorizationCode,

	@Schema(description = "애플이 첫 승인에만 주는 이름을 한 문자열로 조립한 값 (선택). 계정을 새로 만들 때만 닉네임으로 쓰고, "
		+ "2~20자 밖이면 기본 닉네임(필맵러+4자리)으로 대체한다", example = "김필맵")
	String fullName
) {
}
