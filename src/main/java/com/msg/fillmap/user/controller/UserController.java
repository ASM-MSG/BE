package com.msg.fillmap.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.auth.support.RefreshTokenCookies;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.dto.ConsentStatusResponseDto;
import com.msg.fillmap.user.dto.ConsentSubmitRequestDto;
import com.msg.fillmap.user.dto.LocationConsentUpdateRequestDto;
import com.msg.fillmap.user.dto.MarketingConsentUpdateRequestDto;
import com.msg.fillmap.user.dto.NicknameUpdateRequestDto;
import com.msg.fillmap.user.dto.ProfileImagePresignRequestDto;
import com.msg.fillmap.user.dto.ProfileImagePresignResponseDto;
import com.msg.fillmap.user.dto.ProfileImageUpdateRequestDto;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.service.UserService;

@Tag(name = "사용자 (User)", description = "계정 관리 API. 인증 필수 — 본인 계정만 대상이다.")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

	private static final String BEARER_PREFIX = "Bearer ";

	private final UserService userService;

	@Operation(
		summary = "내 프로필 조회",
		description = "소셜 로그인이 자동 저장한 이메일·닉네임을 반환한다. "
			+ "항상 본인 계정만 — 경로에 대상 식별자가 없다."
	)
	@GetMapping("/me")
	public SuccessResponse<UserProfileResponseDto> getMe(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(userService.getMyProfile(principal.userId()));
	}

	@Operation(
		summary = "닉네임 수정",
		description = "닉네임(2~20자)을 교체하고 변경 후 프로필을 반환한다. 중복 닉네임은 허용된다."
	)
	@PutMapping("/me/nickname")
	public SuccessResponse<UserProfileResponseDto> updateNickname(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody NicknameUpdateRequestDto request
	) {
		return SuccessResponse.of(userService.updateNickname(principal.userId(), request.nickname()));
	}

	@Operation(
		summary = "위치정보 사용 동의 켜기",
		description = "위치기반서비스 이용 동의를 켜고 변경 후 프로필을 반환한다. 첫 로그인 온보딩의 동의 제출과 "
			+ "프로필 화면이 이 엔드포인트 하나를 공용으로 쓴다.\n\n"
			+ "이 동의는 철회할 수 없다 — consented=false 요청은 1400 으로 거절된다. 되돌리려면 계정을 삭제해야 "
			+ "하며, 이는 다른 필수 약관 동의와 같은 규칙이다. 이미 켜진 상태에서 다시 켜는 요청은 성공하고, "
			+ "이때 서버가 보관하는 동의 시각은 갱신되지 않는다(멱등)."
	)
	@PutMapping("/me/location-consent")
	public SuccessResponse<UserProfileResponseDto> updateLocationConsent(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody LocationConsentUpdateRequestDto request
	) {
		return SuccessResponse.of(userService.updateLocationConsent(principal.userId(), request.consented()));
	}

	@Operation(
		summary = "가입 약관 동의 상태 조회",
		description = "로그인 직후 동의 게이트를 띄울지 판별하는 재료다. 항목별 동의 여부 5종과 필수 4항목 "
			+ "완료 여부(requiredCompleted)를 함께 반환한다 — 필수 항목 목록이 늘어도 클라이언트가 조립을 "
			+ "고치지 않도록 서버가 계산한다.\n\n"
			+ "위치기반서비스 항목(locationTerms)은 프로필 화면의 위치정보 사용 동의와 같은 한 값이다. "
			+ "이 동의는 철회할 수 없으므로 한 번 true 가 되면 되돌아가지 않고, 필수 동의를 마친 사용자에게 "
			+ "게이트가 다시 뜨는 일도 없다. 동의 시각은 서버에만 보관하고 응답에 싣지 않는다."
	)
	@GetMapping("/me/consents")
	public SuccessResponse<ConsentStatusResponseDto> getConsentStatus(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(userService.getConsentStatus(principal.userId()));
	}

	@Operation(
		summary = "가입 약관 동의 제출",
		description = "가입 게이트의 \"동의하고 시작하기\" 제출이다. 필수 4항목(만 14세 이상·서비스 이용약관·"
			+ "개인정보 수집·이용·위치기반서비스 이용약관)은 true 여야 하고 마케팅만 선택이다 — 하나라도 "
			+ "false 거나 누락이면 400 이며 이때 아무 항목도 저장되지 않는다.\n\n"
			+ "같은 내용을 다시 보내도 성공한다(멱등). 재제출이 필수 4항목의 최초 동의 시각을 덮지 않고, "
			+ "마케팅만 값이 실제로 달라질 때 변경 시각이 갱신된다. 제출은 위치정보 사용 동의도 함께 켜므로 "
			+ "프로필 화면의 위치 동의와 값이 하나다. 응답은 제출 후 동의 상태다."
	)
	@PutMapping("/me/consents")
	public SuccessResponse<ConsentStatusResponseDto> submitConsents(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody ConsentSubmitRequestDto request
	) {
		return SuccessResponse.of(userService.submitConsents(principal.userId(), request));
	}

	@Operation(
		summary = "마케팅 정보 수신 동의 변경",
		description = "가입 후 설정 화면에서 마케팅 수신 동의를 켜거나 끈다. 이미 저장된 값과 같은 값을 다시 "
			+ "보내도 성공하며, 이때 서버가 보관하는 마지막 변경 시각은 갱신되지 않는다(멱등). "
			+ "응답은 변경 후 동의 상태다 — 위치정보 사용 동의 변경과 같은 구조다."
	)
	@PutMapping("/me/marketing-consent")
	public SuccessResponse<ConsentStatusResponseDto> updateMarketingConsent(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody MarketingConsentUpdateRequestDto request
	) {
		return SuccessResponse.of(userService.updateMarketingConsent(principal.userId(), request.consented()));
	}

	@Operation(
		summary = "프로필 이미지 업로드용 presigned URL 발급",
		description = "프로필 이미지를 S3 에 직접 올릴 presigned URL 을 발급한다. 이 URL 로 PUT 업로드한 뒤 "
			+ "받은 s3Key 로 변경 확정(PUT /api/users/me/profile-image)을 호출한다. 허용 형식은 "
			+ "jpg·jpeg·png·webp 이고 크기 상한은 5MB 다 — 확장자와 Content-Type 이 어긋나거나 "
			+ "허용 밖이면 1415, 선언 크기가 상한을 넘으면 1413.\n\n"
			+ "아이폰 사진(heic·heif)은 받지 않는다 — 저장해도 대부분의 브라우저가 표시하지 못하기 때문이다. "
			+ "파일 선택 accept 목록에서 heic 를 빼면 iOS 가 플랫폼 수준에서 JPEG 로 변환해 주므로 "
			+ "정상 경로에서는 거부가 나오지 않고, 그래도 새어 들어온 원본 heic 는 1415 응답을 안내 문구로 처리한다."
	)
	@PostMapping("/me/profile-image/presigned-url")
	public SuccessResponse<ProfileImagePresignResponseDto> issueProfileImagePresignedUrl(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody ProfileImagePresignRequestDto request
	) {
		return SuccessResponse.of(userService.issueProfileImagePresignedUrl(principal.userId(), request));
	}

	@Operation(
		summary = "프로필 이미지 변경 확정",
		description = "presign 으로 올린 pending 키를 확정해 프로필 이미지를 교체하고 갱신된 프로필을 반환한다. "
			+ "내 pending 경로가 아니거나 확장자 없는 키는 1401, S3 에 실제로 없는 키는 1402, 실측 크기가 "
			+ "5MB 를 넘으면 1413. 교체된 이전 이미지는 응답 후 정리된다."
	)
	@PutMapping("/me/profile-image")
	public SuccessResponse<UserProfileResponseDto> updateProfileImage(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody ProfileImageUpdateRequestDto request
	) {
		return SuccessResponse.of(userService.updateProfileImage(principal.userId(), request.s3Key()));
	}

	@Operation(
		summary = "프로필 이미지 제거",
		description = "프로필 이미지를 기본 상태(null)로 되돌린다. 이미 기본 상태여도 성공한다(멱등). "
			+ "응답은 변경 확정과 같은 프로필 형태다."
	)
	@DeleteMapping("/me/profile-image")
	public SuccessResponse<UserProfileResponseDto> removeProfileImage(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(userService.removeProfileImage(principal.userId()));
	}

	@Operation(
		summary = "계정 삭제",
		description = "내 계정을 즉시·비가역 삭제한다. 연쇄 개인 데이터·영상 S3 객체가 제거되고 "
			+ "전 디바이스 세션이 무효화된다. 같은 이메일·카카오 계정으로 다시 로그인하면 신규 가입이다."
	)
	@DeleteMapping("/me")
	public SuccessResponse<Void> deleteMe(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
		HttpServletResponse response
	) {
		// 엔드포인트가 authenticated 라 필터 통과(Bearer 형식)가 보장된다 — logout 같은 null 가드 불필요 (§D4).
		userService.deleteAccount(principal.userId(), authorization.substring(BEARER_PREFIX.length()));
		response.addHeader(HttpHeaders.SET_COOKIE, RefreshTokenCookies.expire());
		return new SuccessResponse<>(null);
	}
}
