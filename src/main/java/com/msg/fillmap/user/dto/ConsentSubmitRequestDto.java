package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * 가입 약관 동의 제출 요청 (MSG-433). 필수 4항목은 @NotNull + @AssertTrue 로 컨트롤러 진입 전에
 * 걸러진다 — 하나라도 false 거나 누락이면 400 이고 서비스에 닿지 않으므로 "아무 항목도 저장되지
 * 않는다"(FR-3)가 구조로 보장된다. @AssertTrue 는 null 을 통과시키므로 @NotNull 병기가 필수다.
 *
 * <p>원시 boolean 이 아니라 Boolean 인 이유는 필드를 빼먹은 요청이 false 로 둔갑하지 않게 하기
 * 위해서다 (LocationConsentUpdateRequestDto 주석 동일).
 */
@Schema(description = "가입 약관 동의 제출 요청. 필수 4항목은 true 여야 하고 마케팅만 선택이다.")
public record ConsentSubmitRequestDto(
	@Schema(description = "만 14세 이상 확인 (필수, true 만 허용)", example = "true")
	@NotNull(message = "만 14세 이상 확인은 필수 항목입니다")
	@AssertTrue(message = "만 14세 이상 확인에 동의해야 합니다")
	Boolean ageOver14,

	@Schema(description = "서비스 이용약관 동의 (필수, true 만 허용)", example = "true")
	@NotNull(message = "서비스 이용약관 동의는 필수 항목입니다")
	@AssertTrue(message = "서비스 이용약관에 동의해야 합니다")
	Boolean serviceTerms,

	@Schema(description = "개인정보 수집·이용 동의 (필수, true 만 허용)", example = "true")
	@NotNull(message = "개인정보 수집·이용 동의는 필수 항목입니다")
	@AssertTrue(message = "개인정보 수집·이용에 동의해야 합니다")
	Boolean privacyPolicy,

	@Schema(description = "위치기반서비스 이용약관 동의 (필수, true 만 허용)", example = "true")
	@NotNull(message = "위치기반서비스 이용약관 동의는 필수 항목입니다")
	@AssertTrue(message = "위치기반서비스 이용약관에 동의해야 합니다")
	Boolean locationTerms,

	@Schema(description = "마케팅 정보 수신 동의 (선택). true·false 모두 유효하되 누락은 400 이다", example = "false")
	@NotNull(message = "마케팅 정보 수신 동의 여부는 필수 항목입니다")
	Boolean marketing
) {
}
