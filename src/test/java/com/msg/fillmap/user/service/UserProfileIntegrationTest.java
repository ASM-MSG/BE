package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 프로필 조회 · 닉네임 수정 (MSG-203 FR-1·2·5, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에
 * 시드를 남기지 않는다 (UserAccountDeletionIntegrationTest 패턴). 사용자 생성은 UUID 이메일이라
 * 실데이터·타 테스트와 무충돌.
 */
@SpringBootTest
@Transactional
@DisplayName("프로필 조회 · 닉네임 수정 (실 DB)")
class UserProfileIntegrationTest {

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	/** 실 S3 호출 차단용 — UserServiceImpl 이 주입받는 계정 삭제 축 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private long userId;
	private String email;

	@BeforeEach
	void setUp() {
		email = "profile-" + UUID.randomUUID() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "채우미")).getId();
	}

	// 검증: FR-USER-01
	@Test
	@DisplayName("내 프로필을 조회하면 이메일과 닉네임을 반환한다 (FR-1)")
	void 내_프로필을_조회하면_이메일과_닉네임을_반환한다() {
		UserProfileResponseDto profile = userService.getMyProfile(userId);

		assertThat(profile.email()).isEqualTo(email);
		assertThat(profile.nickname()).isEqualTo("채우미");
	}

	// 검증: FR-USER-02
	@Test
	@DisplayName("닉네임을 변경하면 변경 후 프로필을 반환한다 (FR-2·D2)")
	void 닉네임을_변경하면_변경_후_프로필을_반환한다() {
		UserProfileResponseDto updated = userService.updateNickname(userId, "새닉네임");

		assertThat(updated.nickname()).isEqualTo("새닉네임");
		assertThat(updated.email()).isEqualTo(email);
	}

	// 검증: FR-USER-02
	@Test
	@DisplayName("닉네임 변경 직후 조회에 변경 값이 반영된다 (FR-5)")
	void 닉네임_변경_직후_조회에_변경_값이_반영된다() {
		userService.updateNickname(userId, "바뀐이름");
		// 1차 캐시 재반환이면 더티 체킹 UPDATE 미실행도 통과해 버린다 — flush+clear 로 DB 재조회를 강제 (Codex 리뷰).
		em.flush();
		em.clear();

		assertThat(userService.getMyProfile(userId).nickname()).isEqualTo("바뀐이름");
	}

	// 검증: FR-USER-04
	@Test
	@DisplayName("이메일 없는 카카오 가입 사용자는 email null 로 조회된다 (MSG-310)")
	void 이메일_없는_카카오_가입_사용자는_email_null_로_조회된다() {
		long kakaoUserId = userRepository
			.save(User.createOAuthUser(AuthProvider.KAKAO, "oid-" + UUID.randomUUID(), null, "카카오유저"))
			.getId();

		UserProfileResponseDto profile = userService.getMyProfile(kakaoUserId);

		assertThat(profile.email()).isNull();
		assertThat(profile.nickname()).isEqualTo("카카오유저");
	}

	@Test
	@DisplayName("존재하지 않는 사용자 조회는 USER_NOT_FOUND 다 (1404)")
	void 존재하지_않는_사용자_조회는_USER_NOT_FOUND_예외를_던진다() {
		assertThatThrownBy(() -> userService.getMyProfile(-1L))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
	}

	@Test
	@DisplayName("존재하지 않는 사용자 닉네임 변경은 USER_NOT_FOUND 다 (1404)")
	void 존재하지_않는_사용자_닉네임_변경은_USER_NOT_FOUND_예외를_던진다() {
		assertThatThrownBy(() -> userService.updateNickname(-1L, "새닉네임"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
	}
}
