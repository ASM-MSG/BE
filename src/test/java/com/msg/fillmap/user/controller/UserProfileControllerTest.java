package com.msg.fillmap.user.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.InMemoryInvalidatedTokenStore;
import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.jwt.JwtTokenProvider;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.ProfileImagePresignRequestDto;
import com.msg.fillmap.user.dto.ProfileImagePresignResponseDto;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.service.UserService;

/**
 * 프로필 조회 · 닉네임 수정 · 프로필 이미지 컨트롤러 (MSG-203 FR-1~4, MSG-373). BadgeControllerTest
 * 패턴 미러 — TokenProvider 실 Bearer + @MockitoBean 정확값 스텁(principal userId 전달 검증).
 * 계정 삭제(DELETE /me)와 컨트롤러를 공유하지만 축이 달라 테스트 클래스는 분리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("UserController 프로필 조회 · 닉네임 수정 · 프로필 이미지")
class UserProfileControllerTest {

	private static final long USER_ID = 42L;
	private static final String ME_URL = "/api/users/me";
	private static final String NICKNAME_URL = "/api/users/me/nickname";
	private static final String PROFILE_IMAGE_URL = "/api/users/me/profile-image";
	private static final String PROFILE_IMAGE_PRESIGN_URL = "/api/users/me/profile-image/presigned-url";
	private static final String LOCATION_CONSENT_URL = "/api/users/me/location-consent";
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 12, 3, 24, 11);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	/** 무효·만료 토큰을 실제로 만들어 쓰기 위한 서명 재료 (JwtFilterIntegrationTest 패턴). */
	@Autowired
	private JwtProperties jwtProperties;

	@MockitoBean
	private UserService userService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: FR-USER-01
	@Test
	@DisplayName("내 프로필을 조회한다 — 200 · email · nickname (FR-1)")
	void 내_프로필을_조회한다() throws Exception {
		// 정확값 스텁 — principal userId(토큰의 USER_ID)가 서비스에 그대로 전달돼야만 매치된다(사용자 격리).
		given(userService.getMyProfile(USER_ID))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "채우미", null, CREATED_AT, false));

		mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.email").value("user@fillmap.dev"))
			.andExpect(jsonPath("$.data.nickname").value("채우미"));
	}

	// 검증: FR-USER-04
	@Test
	@DisplayName("카카오 가입 사용자는 email 필드가 존재하되 값이 null 이다 (MSG-310, required+nullable)")
	void 카카오_가입_사용자는_email_필드가_존재하되_값이_null_이다() throws Exception {
		given(userService.getMyProfile(USER_ID))
			.willReturn(new UserProfileResponseDto(null, "카카오유저", null, CREATED_AT, false));

		// OpenAPI 계약(required + nullable) 그대로의 와이어 검증 — 필드 존재(hasKey)와 값 null 을 구분해 단언한다.
		mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", hasKey("email")))
			.andExpect(jsonPath("$.data.email").value(nullValue()))
			.andExpect(jsonPath("$.data.nickname").value("카카오유저"));
	}

	// 검증: FR-USER-02
	@Test
	@DisplayName("닉네임을 수정하면 변경 후 프로필을 반환한다 (FR-2·D2)")
	void 닉네임을_수정하면_변경_후_프로필을_반환한다() throws Exception {
		given(userService.updateNickname(USER_ID, "새닉네임"))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "새닉네임", null, CREATED_AT, false));

		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"새닉네임\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.nickname").value("새닉네임"));
	}

	// 검증: FR-USER-02
	@Test
	@DisplayName("빈 닉네임은 400 이다 (FR-3, @NotBlank)")
	void 빈_닉네임은_400을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"\"}"))
			.andExpect(status().isBadRequest());
	}

	// 검증: FR-USER-02
	@Test
	@DisplayName("한 글자 닉네임은 400 이다 (FR-3, @Size 하한 밖)")
	void 한_글자_닉네임은_400을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"가\"}"))
			.andExpect(status().isBadRequest());
	}

	// 검증: FR-USER-02
	@Test
	@DisplayName("스물한 글자 닉네임은 400 이다 (FR-3, @Size 상한 밖)")
	void 스물한_글자_닉네임은_400을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"" + "가".repeat(21) + "\"}"))
			.andExpect(status().isBadRequest());
	}

	// 검증: FR-USER-02
	@Test
	@DisplayName("두 글자·스무 글자 닉네임은 통과한다 (FR-2, @Size 경계 안)")
	void 두_글자와_스무_글자_닉네임은_통과한다() throws Exception {
		given(userService.updateNickname(eq(USER_ID), anyString()))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "무관", null, CREATED_AT, false));

		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"가나\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"" + "가".repeat(20) + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("본문 없는 닉네임 수정은 400 이다 — HttpMessageNotReadable 전역 핸들러 (Codex 리뷰 반영)")
	void 본문_없는_닉네임_수정은_400을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("깨진 JSON 닉네임 수정은 400 이다 — 500 으로 새면 안 된다")
	void 깨진_JSON_닉네임_수정은_400을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("토큰 없는 프로필 조회는 401 이다 (FR-4)")
	void 토큰_없는_프로필_조회는_401을_반환한다() throws Exception {
		mockMvc.perform(get(ME_URL))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("토큰 없는 닉네임 수정은 401 이다 (FR-4)")
	void 토큰_없는_닉네임_수정은_401을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"새닉네임\"}"))
			.andExpect(status().isUnauthorized());
	}

	// 검증: FR-USER-13
	@Test
	@DisplayName("프로필 응답에 이미지 URL 과 가입 시각이 실린다 — 미설정이면 필드 존재 + null (MSG-373 FR-2·3)")
	void 프로필_응답에_이미지_URL과_가입_시각이_실린다() throws Exception {
		given(userService.getMyProfile(USER_ID))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "채우미", null, CREATED_AT, false));

		mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", hasKey("profileImageUrl")))
			.andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()))
			.andExpect(jsonPath("$.data.createdAt").value("2026-01-12T03:24:11Z"));
	}

	// 검증: FR-USER-12
	@Test
	@DisplayName("프로필 이미지 presigned URL 을 발급한다 — 200 · uploadUrl · s3Key (MSG-373 FR-1)")
	void 프로필_이미지_presigned_URL을_발급한다() throws Exception {
		given(userService.issueProfileImagePresignedUrl(
			USER_ID, new ProfileImagePresignRequestDto("jpg", "image/jpeg", 1048576L)))
			.willReturn(new ProfileImagePresignResponseDto("https://s3/put", "profiles/pending/42/uuid.jpg", 600L));

		mockMvc.perform(post(PROFILE_IMAGE_PRESIGN_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"extension\":\"jpg\",\"contentType\":\"image/jpeg\",\"contentLength\":1048576}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.uploadUrl").value("https://s3/put"))
			.andExpect(jsonPath("$.data.s3Key").value("profiles/pending/42/uuid.jpg"))
			.andExpect(jsonPath("$.data.expiresInSec").value(600));
	}

	// 검증: FR-USER-12
	@Test
	@DisplayName("크기 없는 presign 요청은 400 이다 (@NotNull)")
	void 크기_없는_presign_요청은_400을_반환한다() throws Exception {
		mockMvc.perform(post(PROFILE_IMAGE_PRESIGN_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"extension\":\"jpg\",\"contentType\":\"image/jpeg\"}"))
			.andExpect(status().isBadRequest());
	}

	// 검증: FR-USER-12
	@Test
	@DisplayName("프로필 이미지를 변경하면 갱신된 프로필을 반환한다 (MSG-373 FR-1)")
	void 프로필_이미지를_변경하면_갱신된_프로필을_반환한다() throws Exception {
		given(userService.updateProfileImage(USER_ID, "profiles/pending/42/uuid.jpg"))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "채우미", "https://cdn/img.jpg", CREATED_AT, false));

		mockMvc.perform(put(PROFILE_IMAGE_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"s3Key\":\"profiles/pending/42/uuid.jpg\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.profileImageUrl").value("https://cdn/img.jpg"));
	}

	// 검증: FR-USER-12
	@Test
	@DisplayName("빈 s3Key 변경 요청은 400 이다 (@NotBlank)")
	void 빈_s3Key_변경_요청은_400을_반환한다() throws Exception {
		mockMvc.perform(put(PROFILE_IMAGE_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"s3Key\":\"\"}"))
			.andExpect(status().isBadRequest());
	}

	// 검증: FR-USER-12
	@Test
	@DisplayName("프로필 이미지를 제거하면 이미지 URL 이 null 인 프로필을 반환한다 (MSG-373 FR-6)")
	void 프로필_이미지를_제거하면_null인_프로필을_반환한다() throws Exception {
		given(userService.removeProfileImage(USER_ID))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "채우미", null, CREATED_AT, false));

		mockMvc.perform(delete(PROFILE_IMAGE_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));
	}

	// 검증: FR-USER-14
	@Test
	@DisplayName("위치정보 사용 동의를 켜면 변경 후 프로필을 반환한다 (MSG-402 FR-2·D-1)")
	void 위치정보_사용_동의를_켜면_변경_후_프로필을_반환한다() throws Exception {
		given(userService.updateLocationConsent(USER_ID, true))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "채우미", null, CREATED_AT, true));

		mockMvc.perform(put(LOCATION_CONSENT_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consented\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.locationConsent").value(true));
	}

	// 검증: FR-USER-14
	@Test
	@DisplayName("위치정보 사용 동의를 끄는 요청은 400 · 1400 이다 (철회 불가 전환, 2026-08-19 개정)")
	void 위치정보_사용_동의를_끄는_요청은_1400이다() throws Exception {
		// false 는 형식상 유효값이라 Bean Validation 이 못 잡고 서비스가 던진다 — 와이어 계약을 여기서 확인한다.
		given(userService.updateLocationConsent(USER_ID, false))
			.willThrow(new ApiException(UserErrorCode.LOCATION_CONSENT_IRREVOCABLE));

		mockMvc.perform(put(LOCATION_CONSENT_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consented\":false}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(1400));
	}

	// 검증: FR-USER-14
	@Test
	@DisplayName("consented 없는 동의 변경 요청은 400 이다 (@NotNull — 누락이 false 로 둔갑하지 않는다)")
	void consented_없는_요청은_400이다() throws Exception {
		mockMvc.perform(put(LOCATION_CONSENT_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest());
	}

	// 검증: FR-USER-14
	@Test
	@DisplayName("프로필 응답 JSON 에 locationConsent 키가 항상 존재한다 (required 계약)")
	void 프로필_응답_JSON에_locationConsent_키가_항상_존재한다() throws Exception {
		given(userService.getMyProfile(USER_ID))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "채우미", null, CREATED_AT, false));

		mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", hasKey("locationConsent")))
			.andExpect(jsonPath("$.data.locationConsent").value(false));
	}

	@Test
	@DisplayName("토큰 없는 동의 변경은 401 UNAUTHENTICATED (2403) 다")
	void 토큰_없는_동의_변경_요청은_2403이다() throws Exception {
		mockMvc.perform(put(LOCATION_CONSENT_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consented\":true}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}

	@Test
	@DisplayName("변조된 토큰의 동의 변경은 401 INVALID_TOKEN (2401) 이다")
	void 무효_토큰_동의_변경_요청은_2401이다() throws Exception {
		JwtProperties otherProps = new JwtProperties(
			"another-completely-different-secret-32-bytes-plus-long", Duration.ofHours(1),
			jwtProperties.refreshSecret(), jwtProperties.refreshTokenTtl());
		String forged = new JwtTokenProvider(otherProps, new InMemoryInvalidatedTokenStore())
			.issueAccessToken(USER_ID, UserRole.USER);

		mockMvc.perform(put(LOCATION_CONSENT_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consented\":true}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2401));
	}

	@Test
	@DisplayName("만료된 토큰의 동의 변경은 401 EXPIRED_TOKEN (2402) 이다")
	void 만료_토큰_동의_변경_요청은_2402이다() throws Exception {
		JwtProperties expiredProps = new JwtProperties(
			jwtProperties.secret(), Duration.ofSeconds(-1),
			jwtProperties.refreshSecret(), jwtProperties.refreshTokenTtl());
		String expired = new JwtTokenProvider(expiredProps, new InMemoryInvalidatedTokenStore())
			.issueAccessToken(USER_ID, UserRole.USER);

		mockMvc.perform(put(LOCATION_CONSENT_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consented\":true}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2402));
	}

	@Test
	@DisplayName("토큰 없는 프로필 이미지 요청은 401 이다 (비기능 — 본인만 변경)")
	void 토큰_없는_프로필_이미지_요청은_401을_반환한다() throws Exception {
		mockMvc.perform(put(PROFILE_IMAGE_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"s3Key\":\"profiles/pending/42/uuid.jpg\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete(PROFILE_IMAGE_URL))
			.andExpect(status().isUnauthorized());
	}
}
