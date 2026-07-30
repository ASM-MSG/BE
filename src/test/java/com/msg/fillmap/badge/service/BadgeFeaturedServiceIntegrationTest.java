package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.badge.dto.FeaturedBadgeResponseDto;
import com.msg.fillmap.badge.exception.BadgeErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 대표 뱃지 교체 통합 검증 (실 PostGIS, MSG-239 모듈 3 §D6). 7403 의 "전체 롤백(부분 적용 없음)"을
 * 실제 커밋 경계로 관찰해야 하므로 @Transactional 격리를 못 쓴다 — 합성 유저·획득 뱃지를 커밋해 두고
 * 서비스가 자기 트랜잭션으로 돌게 한 뒤, @AfterEach 에서 대상 지정 삭제로 걷어낸다
 * (users 삭제가 user_badges 를 cascade — badges 마스터 불가침, VideoRegionStatsAtomicityTest 선례).
 */
@SpringBootTest
@DisplayName("BadgeFeaturedService 대표 뱃지 교체 (실 PostGIS)")
class BadgeFeaturedServiceIntegrationTest {

	@Autowired
	private BadgeFeaturedService badgeFeaturedService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long me;
	private long other;
	private long explorer1Id;
	private long explorer10Id;
	private long explorer50Id;   // 마스터엔 있지만 아무도 획득하지 않은 뱃지

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		explorer1Id = badgeId("EXPLORER_1");
		explorer10Id = badgeId("EXPLORER_10");
		explorer50Id = badgeId("EXPLORER_50");
		tx.executeWithoutResult(status -> {
			me = newUser("featured-me");
			other = newUser("featured-other");
			grant(me, explorer1Id);
			grant(me, explorer10Id);
			grant(other, explorer1Id);   // 같은 badgeId 를 가진 두 유저 — userId 스코프 검증용
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 유저만 삭제 — user_badges 는 ON DELETE CASCADE 로 함께 걷힌다.
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id IN (:me, :other)")
				.setParameter("me", me)
				.setParameter("other", other)
				.executeUpdate());
	}

	@Test
	@DisplayName("획득한 뱃지 2개를 순서대로 대표로 지정한다 — rank 1·2 = 배열 순서")
	void 획득한_뱃지_2개를_순서대로_대표로_지정한다() {
		List<FeaturedBadgeResponseDto> result =
			badgeFeaturedService.replaceFeatured(me, List.of(explorer10Id, explorer1Id));

		assertThat(result).extracting(FeaturedBadgeResponseDto::badgeId)
			.containsExactly(explorer10Id, explorer1Id);
		assertThat(result).extracting(FeaturedBadgeResponseDto::rank).containsExactly(1, 2);
		assertThat(result).extracting(FeaturedBadgeResponseDto::code)
			.containsExactly("EXPLORER_10", "EXPLORER_1");
	}

	@Test
	@DisplayName("대표 뱃지 교체는 기존 대표를 전부 대체한다 — replace-set 멱등")
	void 대표_뱃지_교체는_기존_대표를_전부_대체한다() {
		badgeFeaturedService.replaceFeatured(me, List.of(explorer1Id, explorer10Id));

		List<FeaturedBadgeResponseDto> result = badgeFeaturedService.replaceFeatured(me, List.of(explorer10Id));

		assertThat(result).extracting(FeaturedBadgeResponseDto::badgeId).containsExactly(explorer10Id);
		assertThat(result).extracting(FeaturedBadgeResponseDto::rank).containsExactly(1);
		assertThat(featuredRows(me)).containsExactly(explorer10Id);
	}

	@Test
	@DisplayName("빈 배열이면 대표 뱃지가 전부 해제된다")
	void 빈_배열이면_대표_뱃지가_전부_해제된다() {
		badgeFeaturedService.replaceFeatured(me, List.of(explorer1Id));

		List<FeaturedBadgeResponseDto> result = badgeFeaturedService.replaceFeatured(me, List.of());

		assertThat(result).isEmpty();
		assertThat(featuredRows(me)).isEmpty();
	}

	@Test
	@DisplayName("획득하지 않은 뱃지를 지정하면 7403 이다 — 미존재 badge id 포함 동일(§D6)")
	void 획득하지_않은_뱃지를_지정하면_7403이다() {
		assertThatThrownBy(() -> badgeFeaturedService.replaceFeatured(me, List.of(explorer50Id)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", BadgeErrorCode.BADGE_NOT_EARNED);
		assertThatThrownBy(() -> badgeFeaturedService.replaceFeatured(me, List.of(999_999_999L)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", BadgeErrorCode.BADGE_NOT_EARNED);
	}

	@Test
	@DisplayName("중복 badgeId 는 7400 이다")
	void 중복_badgeId는_7400이다() {
		assertThatThrownBy(() -> badgeFeaturedService.replaceFeatured(me, List.of(explorer1Id, explorer1Id)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", BadgeErrorCode.INVALID_FEATURED_BADGES);
	}

	@Test
	@DisplayName("미획득 뱃지가 섞이면 전체가 롤백되어 부분 적용되지 않는다")
	void 미획득_뱃지가_섞이면_전체가_롤백되어_부분_적용되지_않는다() {
		badgeFeaturedService.replaceFeatured(me, List.of(explorer1Id));

		// rank 1(explorer10, 보유)까지 지정된 뒤 2번째(미보유)에서 7403 — 전 해제·rank1 지정이 함께 롤백된다.
		assertThatThrownBy(() -> badgeFeaturedService.replaceFeatured(me, List.of(explorer10Id, explorer50Id)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", BadgeErrorCode.BADGE_NOT_EARNED);

		assertThat(featuredRows(me)).containsExactly(explorer1Id);   // 기존 대표 그대로
	}

	@Test
	@DisplayName("타인 뱃지에는 영향을 줄 수 없다 — userId 스코프")
	void 타인_뱃지에는_영향을_줄_수_없다() {
		badgeFeaturedService.replaceFeatured(me, List.of(explorer1Id, explorer10Id));

		badgeFeaturedService.replaceFeatured(other, List.of(explorer1Id));

		// 같은 badgeId(EXPLORER_1)를 대표로 써도 서로의 rows 는 분리돼 있다.
		assertThat(featuredRows(me)).containsExactly(explorer1Id, explorer10Id);
		assertThat(featuredRows(other)).containsExactly(explorer1Id);
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "대표뱃지테스터")).getId();
	}

	private void grant(long userId, long badgeId) {
		em.createNativeQuery("INSERT INTO user_badges (user_id, badge_id) VALUES (:userId, :badgeId)")
			.setParameter("userId", userId)
			.setParameter("badgeId", badgeId)
			.executeUpdate();
	}

	private long badgeId(String code) {
		return ((Number) em.createNativeQuery("SELECT id FROM badges WHERE code = :code")
			.setParameter("code", code)
			.getSingleResult()).longValue();
	}

	/** featured_rank 순 badge_id 목록 — DB 상태 직접 관찰용. */
	private List<Long> featuredRows(long userId) {
		List<?> rows = em.createNativeQuery("""
				SELECT badge_id FROM user_badges
				WHERE user_id = :userId AND featured_rank IS NOT NULL
				ORDER BY featured_rank
				""")
			.setParameter("userId", userId)
			.getResultList();
		return rows.stream().map(row -> ((Number) row).longValue()).toList();
	}
}
