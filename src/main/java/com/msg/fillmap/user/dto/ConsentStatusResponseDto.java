package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.User;

/**
 * 가입 약관 동의 상태 응답 (MSG-433). 동의 상태 조회·동의 제출·마케팅 변경이 같은 형태를 반환해
 * FE 가 재조회 없이 게이트 통과를 확정한다.
 *
 * <p>필수 3항목은 저장된 동의 시각의 NULL 여부가 곧 동의 여부다 (§D-2). 동의 시각 자체는 어떤
 * 응답에도 싣지 않는다 — 법적 증빙용 서버 보관 값이다 (§D-7, MSG-402 §D-6 동형).
 *
 * <p>requiredCompleted 는 필수 4항목 AND 를 서버가 계산해 동봉한다 (§D-8). 웹·모바일이 각자
 * 조립하면 항목이 늘 때 어긋나므로 게이트 판별 규칙의 정본을 서버 한 곳에 둔다.
 */
@Schema(description = "가입 약관 동의 상태. 조회·제출·마케팅 변경이 같은 형태를 반환한다.",
	requiredProperties = {"ageOver14", "serviceTerms", "privacyPolicy", "locationTerms", "marketing",
		"requiredCompleted"})
public record ConsentStatusResponseDto(
	@Schema(description = "만 14세 이상 확인 여부 (필수). 자기 확인 체크 사실만 저장하며 생년월일은 수집하지 않는다",
		example = "true")
	Boolean ageOver14,

	@Schema(description = "서비스 이용약관 동의 여부 (필수)", example = "true")
	Boolean serviceTerms,

	@Schema(description = "개인정보 수집·이용 동의 여부 (필수)", example = "true")
	Boolean privacyPolicy,

	@Schema(description = "위치기반서비스 이용약관 동의 여부 (필수). 프로필 화면의 위치정보 사용 동의와 같은 한 값이며 "
		+ "철회할 수 없다 — 한 번 true 가 되면 되돌아가지 않는다", example = "true")
	Boolean locationTerms,

	@Schema(description = "마케팅 정보 수신 동의 여부 (선택). 가입 후에도 전용 API 로 켜고 끌 수 있다", example = "false")
	Boolean marketing,

	@Schema(description = "필수 4항목을 전부 동의했으면 true. false 면 클라이언트가 동의 게이트를 띄운다", example = "true")
	Boolean requiredCompleted
) {

	public static ConsentStatusResponseDto from(User user) {
		boolean ageOver14 = user.getAgeOver14ConsentedAt() != null;
		boolean serviceTerms = user.getServiceTermsConsentedAt() != null;
		boolean privacyPolicy = user.getPrivacyConsentedAt() != null;
		boolean locationTerms = user.isLocationConsent();
		return new ConsentStatusResponseDto(ageOver14, serviceTerms, privacyPolicy, locationTerms,
			user.isMarketingConsent(),
			ageOver14 && serviceTerms && privacyPolicy && locationTerms);
	}
}
