package com.msg.fillmap.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.notification.dto.PushTokenRequestDto;
import com.msg.fillmap.notification.entity.PushPlatform;
import com.msg.fillmap.notification.entity.PushToken;
import com.msg.fillmap.notification.repository.PushTokenRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 푸시 토큰 UPSERT/해제 통합 검증 (실 PostGIS, MSG-178). ON CONFLICT 갱신·FK CASCADE 를 실제 DB 로
 * 관찰해야 하므로 @Transactional 격리를 못 쓴다 — 합성 유저를 커밋해 두고 서비스가 자기 트랜잭션으로
 * 돌게 한 뒤, @AfterEach 에서 users 대상 지정 삭제로 걷어낸다 (push_tokens 는 ON DELETE CASCADE 로
 * 함께 걷힘 — BadgeFeaturedServiceIntegrationTest 선례).
 */
@SpringBootTest
@DisplayName("PushTokenService 토큰 등록 UPSERT·해제 (실 PostGIS)")
class PushTokenServiceIntegrationTest {

	@Autowired
	private PushTokenService pushTokenService;

	@Autowired
	private PushTokenRepository pushTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long me;
	private long other;
	private String token;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		token = "msg178-token-" + System.nanoTime();   // 공유 로컬 DB — 실행 간 충돌 방지
		tx.executeWithoutResult(status -> {
			me = newUser("push-me");
			other = newUser("push-other");
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 유저만 삭제 — push_tokens 는 ON DELETE CASCADE 로 함께 걷힌다.
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id IN (:me, :other)")
				.setParameter("me", me)
				.setParameter("other", other)
				.executeUpdate());
	}

	@Test
	@DisplayName("새 토큰 등록은 행을 만들고 last_used_at 을 채운다")
	void 새_토큰_등록은_행을_만들고_last_used_at을_채운다() {
		pushTokenService.register(me, new PushTokenRequestDto(token, "IOS", "1.0.0"));

		PushToken saved = pushTokenRepository.findById(token).orElseThrow();
		assertThat(saved.getUserId()).isEqualTo(me);
		assertThat(saved.getPlatform()).isEqualTo(PushPlatform.IOS);
		assertThat(saved.getAppVersion()).isEqualTo("1.0.0");
		assertThat(saved.getLastUsedAt()).isNotNull();
	}

	@Test
	@DisplayName("같은 토큰 재등록은 충돌 없이 전 필드를 갱신한다 — 행 1개 유지")
	void 같은_토큰_재등록은_충돌_없이_전_필드를_갱신한다() {
		pushTokenService.register(me, new PushTokenRequestDto(token, "IOS", "1.0.0"));

		// 소문자 platform — 대소문자 무시 파싱까지 실 서비스로 함께 관찰한다.
		pushTokenService.register(me, new PushTokenRequestDto(token, "android", "1.1.0"));

		assertThat(rowCount(token)).isEqualTo(1);
		PushToken saved = pushTokenRepository.findById(token).orElseThrow();
		assertThat(saved.getPlatform()).isEqualTo(PushPlatform.ANDROID);
		assertThat(saved.getAppVersion()).isEqualTo("1.1.0");
	}

	@Test
	@DisplayName("다른 계정 재로그인 토큰은 새 user_id 로 넘어간다 — 계정 전환 이관 (핵심 확정 요구)")
	void 다른_계정_재로그인_토큰은_새_user_id로_넘어간다() {
		pushTokenService.register(me, new PushTokenRequestDto(token, "WEB", "1.0.0"));

		pushTokenService.register(other, new PushTokenRequestDto(token, "WEB", "1.0.0"));

		assertThat(rowCount(token)).isEqualTo(1);
		assertThat(pushTokenRepository.findById(token).orElseThrow().getUserId()).isEqualTo(other);
	}

	@Test
	@DisplayName("없는 토큰 해제도 예외 없이 끝난다 — 멱등")
	void 없는_토큰_해제도_예외_없이_끝난다() {
		assertThatCode(() -> pushTokenService.unregister("msg178-missing-" + System.nanoTime()))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("해제하면 행이 삭제된다")
	void 해제하면_행이_삭제된다() {
		pushTokenService.register(me, new PushTokenRequestDto(token, "WEB", null));

		pushTokenService.unregister(token);

		assertThat(pushTokenRepository.findById(token)).isEmpty();
	}

	@Test
	@DisplayName("사용자 삭제 시 토큰이 CASCADE 로 사라진다 — 코드 0줄 근거 (FK 실측)")
	void 사용자_삭제_시_토큰이_CASCADE로_사라진다() {
		pushTokenService.register(me, new PushTokenRequestDto(token, "ANDROID", "1.0.0"));

		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :me")
				.setParameter("me", me)
				.executeUpdate());

		assertThat(pushTokenRepository.findById(token)).isEmpty();
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "푸시토큰테스터")).getId();
	}

	/** fcm_token 은 PK 라 0 또는 1 — UPSERT 가 중복 행을 만들지 않았음을 DB 로 직접 관찰한다. */
	private long rowCount(String fcmToken) {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM push_tokens WHERE fcm_token = :token")
			.setParameter("token", fcmToken)
			.getSingleResult()).longValue();
	}
}
