package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.RefreshTokenStore;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 계정 삭제 — 커밋 후 세션 무효화 (MSG-205 FR-4·§D5, 실 Redis localhost:6379).
 *
 * 트랜잭션 없이 돌린다 — refresh 소멸·블랙리스트는 afterCommit 에서 실행되므로 @Transactional
 * 테스트(항상 롤백)에서는 영영 실행되지 않는다. users 행은 실제로 커밋·삭제되고, 실패 경로 대비
 * 잔여 정리는 @AfterEach 가 맡는다 (공유 로컬 DB 시드 잔류 금지). Redis 블랙리스트 키는
 * 토큰 해시 키 + 액세스 TTL 자연 소멸이라 무해하다 (RedisRefreshTokenStoreTest 선례).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("계정 삭제 · 세션 무효화 (실 Redis)")
class UserAccountSessionInvalidationIntegrationTest {

	private static final String DELETE_ME_URL = "/api/users/me";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenService refreshTokenService;

	@Autowired
	private RefreshTokenStore refreshTokenStore;

	@Autowired
	private TokenProvider tokenProvider;

	/** 영상 없는 유저라 S3 호출 자체가 없어야 하지만, 실 S3 접근을 원천 차단한다. */
	@MockitoBean
	private S3Client s3Client;

	private Long userId;
	private String accessToken;

	@BeforeEach
	void setUp() {
		userId = userRepository.save(
			User.createLocalUser("session-" + UUID.randomUUID() + "@example.com", "hash", "세션테스터")).getId();
		accessToken = tokenProvider.issueAccessToken(userId, UserRole.USER);
	}

	@AfterEach
	void tearDown() {
		// 정상 경로면 이미 삭제·소멸 상태 — 실패 경로 대비 멱등 정리.
		userRepository.deleteById(userId);
		refreshTokenStore.deleteAll(userId);
	}

	@Test
	void 삭제_후_전_디바이스_refresh_세션이_소멸한다() {
		refreshTokenService.issue(userId, "device-a");
		refreshTokenService.issue(userId, "device-b");

		userService.deleteAccount(userId, accessToken);

		assertThat(refreshTokenStore.findJti(userId, "device-a")).isNull();
		assertThat(refreshTokenStore.findJti(userId, "device-b")).isNull();
	}

	@Test
	void 삭제에_쓴_액세스_토큰은_블랙리스트되어_후속_호출이_401이다() throws Exception {
		// 컨트롤러 경유 전체 경로 — 삭제는 200 + refresh 쿠키 만료.
		mockMvc.perform(delete(DELETE_ME_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));

		// 같은 토큰의 후속 호출 — 필터의 블랙리스트 검사가 2401 로 거른다 (1404 까지 못 간다).
		mockMvc.perform(delete(DELETE_ME_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2401));
	}

	@Test
	void 삭제_후_reissue는_INVALID_REFRESH_TOKEN으로_거부된다() {
		// §D5 앵커: reissue 의 findById 게이트가 삭제된 유저의 재발급을 차단한다 — 이 게이트가
		// 제거되면 삭제-reissue 경합 수용 논리가 무너진다. deleteAll 을 빠져나간 잔여 세션(경합 창의
		// 새어난 키)을 재현하기 위해 삭제 후 세션을 되살려 store 검사는 통과시킨다.
		String refreshToken = refreshTokenService.issue(userId, "device-race");
		String jti = refreshTokenStore.findJti(userId, "device-race");

		userService.deleteAccount(userId, accessToken);
		refreshTokenStore.save(userId, "device-race", jti, Duration.ofMinutes(1));

		assertThatThrownBy(() -> refreshTokenService.reissue(refreshToken))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_REFRESH_TOKEN);
	}
}
