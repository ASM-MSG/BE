package com.msg.fillmap.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.dto.NotificationPreferenceResponseDto;
import com.msg.fillmap.notification.dto.NotificationPreferenceResponseDto.CategoryPreferenceDto;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.exception.NotificationErrorCode;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 알림 수신 설정 통합 검증 (실 PostGIS, MSG-180). ON CONFLICT 멱등·복합 PK 격리를 실제 DB 로
 * 관찰해야 하므로 @Transactional 격리를 못 쓴다 — 합성 유저를 커밋해 두고 서비스가 자기 트랜잭션으로
 * 돌게 한 뒤, @AfterEach 에서 users 대상 지정 삭제로 걷어낸다 (notification_opt_outs 는
 * ON DELETE CASCADE 로 함께 걷힘 — PushTokenServiceIntegrationTest 선례).
 */
@SpringBootTest
@DisplayName("NotificationPreferenceService 수신 설정 저장·조회·isEnabled (실 PostGIS)")
class NotificationPreferenceServiceIntegrationTest {

	@Autowired
	private NotificationPreferenceService notificationPreferenceService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long me;
	private long other;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			me = newUser("pref-me");
			other = newUser("pref-other");
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 유저만 삭제 — notification_opt_outs 는 ON DELETE CASCADE 로 함께 걷힌다.
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id IN (:me, :other)")
				.setParameter("me", me)
				.setParameter("other", other)
				.executeUpdate());
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("설정 행이 없으면 모든 카테고리가 on 이다 — FR-7 opt-out 기본 (179 스텁 테스트 부재 해소)")
	void 설정_행이_없으면_모든_카테고리가_on이다() {
		NotificationPreferenceResponseDto response = notificationPreferenceService.getPreferences(me);

		assertThat(response.preferences())
			.extracting(CategoryPreferenceDto::enabled)
			.containsOnly(true);
		for (NotificationCategory category : NotificationCategory.values()) {
			assertThat(notificationPreferenceService.isEnabled(me, category)).isTrue();
		}
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("카테고리를 off 하면 isEnabled 가 false 다 — FR-8 접점의 실구현 판정")
	void 카테고리를_off하면_isEnabled가_false다() {
		NotificationPreferenceResponseDto response = notificationPreferenceService.update(me, "HOTZONE", false);

		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.HOTZONE)).isFalse();
		// 나머지 카테고리는 영향 없음 + 변경 후 상태 반환이 조회와 일치
		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.BADGE)).isTrue();
		assertThat(response.preferences())
			.extracting(CategoryPreferenceDto::category, CategoryPreferenceDto::enabled)
			.containsExactly(
				tuple(NotificationCategory.BADGE, true),
				tuple(NotificationCategory.HOTZONE, false),
				tuple(NotificationCategory.REMIND, true),
				tuple(NotificationCategory.VIDEO, true),
				tuple(NotificationCategory.WEEKLY, true),
				tuple(NotificationCategory.FRIEND, true));
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("off 를 다시 on 하면 행이 삭제되고 isEnabled 가 true 다")
	void off를_다시_on하면_행이_삭제되고_isEnabled가_true다() {
		notificationPreferenceService.update(me, "REMIND", false);

		notificationPreferenceService.update(me, "REMIND", true);

		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.REMIND)).isTrue();
		assertThat(optOutCount(me)).isZero();
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("off 중복 저장은 멱등이다 — ON CONFLICT DO NOTHING 경로, 행 1개 유지")
	void off_중복_저장은_멱등이다() {
		notificationPreferenceService.update(me, "BADGE", false);

		NotificationPreferenceResponseDto response = notificationPreferenceService.update(me, "BADGE", false);

		assertThat(optOutCount(me)).isEqualTo(1);
		assertThat(response.preferences())
			.filteredOn(preference -> preference.category() == NotificationCategory.BADGE)
			.extracting(CategoryPreferenceDto::enabled)
			.containsExactly(false);
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("조회는 카테고리 6종 전부를 반환한다 — 부재 카테고리 on 합성, enum 선언 순 (MSG-416 FRIEND 포함)")
	void 조회는_카테고리_6종_전부를_반환한다() {
		notificationPreferenceService.update(me, "HOTZONE", false);

		NotificationPreferenceResponseDto response = notificationPreferenceService.getPreferences(me);

		assertThat(response.preferences())
			.extracting(CategoryPreferenceDto::category, CategoryPreferenceDto::enabled)
			.containsExactly(
				tuple(NotificationCategory.BADGE, true),
				tuple(NotificationCategory.HOTZONE, false),
				tuple(NotificationCategory.REMIND, true),
				tuple(NotificationCategory.VIDEO, true),
				tuple(NotificationCategory.WEEKLY, true),
				tuple(NotificationCategory.FRIEND, true));
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("WEEKLY 카테고리를 토글하면 조회에 반영된다 — off/on 왕복 (MSG-315 FR-7)")
	void WEEKLY_카테고리를_토글하면_조회에_반영된다() {
		notificationPreferenceService.update(me, "WEEKLY", false);
		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.WEEKLY)).isFalse();
		assertThat(notificationPreferenceService.getPreferences(me).preferences())
			.filteredOn(preference -> preference.category() == NotificationCategory.WEEKLY)
			.extracting(CategoryPreferenceDto::enabled)
			.containsExactly(false);

		notificationPreferenceService.update(me, "WEEKLY", true);

		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.WEEKLY)).isTrue();
		assertThat(optOutCount(me)).isZero();
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("VIDEO 카테고리를 토글하면 조회에 반영된다 — off/on 왕복 (MSG-313 FR-7)")
	void VIDEO_카테고리를_토글하면_조회에_반영된다() {
		notificationPreferenceService.update(me, "VIDEO", false);
		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.VIDEO)).isFalse();
		assertThat(notificationPreferenceService.getPreferences(me).preferences())
			.filteredOn(preference -> preference.category() == NotificationCategory.VIDEO)
			.extracting(CategoryPreferenceDto::enabled)
			.containsExactly(false);

		notificationPreferenceService.update(me, "VIDEO", true);

		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.VIDEO)).isTrue();
		assertThat(optOutCount(me)).isZero();
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("잘못된 카테고리는 10420 이다 — 소문자 badge 는 성공(대소문자 무시), 미존재 NOPE 는 10420")
	void 잘못된_카테고리는_10420이다() {
		notificationPreferenceService.update(me, "badge", false);
		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.BADGE)).isFalse();

		// 프로브는 enum 에 영원히 없을 문자열 — FRIEND 는 MSG-416 에서 유효값이 됐다 (D6 프로브 교체).
		assertThatThrownBy(() -> notificationPreferenceService.update(me, "NOPE", false))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.INVALID_CATEGORY);
	}

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("FRIEND 카테고리를 끄고 켤 수 있다 — 기본 켜짐, off/on 왕복 (MSG-416 FR-6)")
	void FRIEND_카테고리를_끄고_켤_수_있다() {
		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.FRIEND)).isTrue();

		notificationPreferenceService.update(me, "FRIEND", false);
		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.FRIEND)).isFalse();
		assertThat(notificationPreferenceService.getPreferences(me).preferences())
			.filteredOn(preference -> preference.category() == NotificationCategory.FRIEND)
			.extracting(CategoryPreferenceDto::enabled)
			.containsExactly(false);

		notificationPreferenceService.update(me, "FRIEND", true);

		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.FRIEND)).isTrue();
		assertThat(optOutCount(me)).isZero();
	}

	// 검증: FR-NOTI-15
	@Test
	@DisplayName("알림 설정 응답에 MODERATION 이 없다 — 수신 거부 불가 카테고리는 설정 표면 비노출 (MSG-417 FR-7)")
	void 알림_설정_응답에_MODERATION이_없다() {
		NotificationPreferenceResponseDto response = notificationPreferenceService.getPreferences(me);

		assertThat(response.preferences()).hasSize(6);
		assertThat(response.preferences())
			.extracting(CategoryPreferenceDto::category)
			.doesNotContain(NotificationCategory.MODERATION);
	}

	// 검증: FR-NOTI-15
	@Test
	@DisplayName("MODERATION 토글 요청은 10420 이다 — 존재하지 않는 값과 동일 취급, 대소문자 무시 포함 (MSG-417 FR-7)")
	void MODERATION_토글_요청은_INVALID_CATEGORY다() {
		assertThatThrownBy(() -> notificationPreferenceService.update(me, "MODERATION", false))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.INVALID_CATEGORY);
		assertThatThrownBy(() -> notificationPreferenceService.update(me, "moderation", false))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.INVALID_CATEGORY);
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("다른 사용자의 off 는 내 설정에 영향이 없다 — 복합 PK 격리")
	void 다른_사용자의_off는_내_설정에_영향이_없다() {
		notificationPreferenceService.update(other, "HOTZONE", false);

		assertThat(notificationPreferenceService.isEnabled(me, NotificationCategory.HOTZONE)).isTrue();
		assertThat(notificationPreferenceService.isEnabled(other, NotificationCategory.HOTZONE)).isFalse();
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "알림설정테스터")).getId();
	}

	/** off 행 수를 DB 로 직접 관찰 — ON CONFLICT 가 중복 행을 만들지 않았음을 확인한다. */
	private long optOutCount(long userId) {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM notification_opt_outs WHERE user_id = :userId")
			.setParameter("userId", userId)
			.getSingleResult()).longValue();
	}
}
