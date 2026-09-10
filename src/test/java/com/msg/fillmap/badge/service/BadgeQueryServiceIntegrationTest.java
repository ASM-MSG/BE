package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.badge.dto.MyBadgeResponseDto;
import com.msg.fillmap.badge.repository.UserBadgeRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 내 뱃지 목록 조회·미확인 스탬프 통합 검증 (실 PostGIS, docs/spec/MSG-201.md 테스트 모듈 1). 공유 로컬 DB —
 * 합성 유저만 만들고 @Transactional 롤백, badges 마스터(V9~V12 시딩분)는 불가침·단언은 code 기준.
 * 스탬프 UPDATE 는 같은 트랜잭션 내 재조회(서비스 2회 호출·native SELECT)로 관찰한다.
 * 스펙의 "미시딩 축" 예시(STREAK 등)는 작성 후 V10·V12 로 시딩돼 자동 노출됐다 — 스펙이 예고한
 * 무변경 동작이므로 목록 크기 단언은 "마스터 전체와 일치"로 한다.
 */
@SpringBootTest
@Transactional
@DisplayName("BadgeQueryService 내 뱃지 목록·미확인 스탬프 (실 PostGIS)")
class BadgeQueryServiceIntegrationTest {

	/** V9 활성 3축 11종 — 개수 고정 단언 대신 포함 여부로 쓴다(이후 시딩분이 늘어도 green). */
	private static final List<String> ACTIVE_CODES = List.of(
		"EXPLORER_1", "EXPLORER_10", "EXPLORER_50", "EXPLORER_100", "EXPLORER_500",
		"RECORDER_10", "RECORDER_50", "RECORDER_100",
		"REGION_MASTER_10", "REGION_MASTER_25", "REGION_MASTER_50");

	@Autowired
	private BadgeQueryService badgeQueryService;

	@Autowired
	private UserBadgeRepository userBadgeRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long me;
	private long other;
	private long explorer1Id;
	private long explorer10Id;

	@BeforeEach
	void setUp() {
		me = newUser("badge-query-me");
		other = newUser("badge-query-other");
		explorer1Id = badgeId("EXPLORER_1");
		explorer10Id = badgeId("EXPLORER_10");
	}

	// 검증: FR-BADGE-07
	@Test
	@DisplayName("전체 뱃지 목록은 획득과 미획득을 모두 포함해 시딩 순으로 반환한다 — §D2·D6")
	void 전체_뱃지_목록은_획득과_미획득을_모두_포함해_시딩_순으로_반환한다() {
		grantRetro(me, explorer1Id);

		List<MyBadgeResponseDto> result = badgeQueryService.findMyBadges(me);

		assertThat(result).extracting(MyBadgeResponseDto::code).containsAll(ACTIVE_CODES);
		assertThat(result).isSortedAccordingTo(Comparator.comparing(MyBadgeResponseDto::badgeId));
		assertThat(result).filteredOn(MyBadgeResponseDto::earned)
			.extracting(MyBadgeResponseDto::code).containsExactly("EXPLORER_1");
		assertThat(result).filteredOn(row -> !row.earned()).isNotEmpty();
	}

	// 검증: FR-BADGE-01
	@Test
	@DisplayName("미시딩 축의 뱃지는 목록에 없다 — 마스터 driven, 목록 = badges 전체(§성공 기준 1)")
	void 미시딩_축의_뱃지는_목록에_없다() {
		List<MyBadgeResponseDto> result = badgeQueryService.findMyBadges(me);

		// 목록 행 수 = 현역 마스터 행 수 — 시딩 안 된 축(현재 SPECIAL)은 행 자체가 없다는 것과 동치.
		// 은퇴 뱃지(MSG-363 §D2)는 미획득자인 me 에게 안 보이므로 마스터 카운트에서도 뺀다.
		Number masterCount = (Number) em.createNativeQuery(
			"SELECT COUNT(*) FROM badges WHERE retired_at IS NULL").getSingleResult();
		assertThat(result).hasSize(masterCount.intValue());
	}

	// 검증: FR-BADGE-07
	@Test
	@DisplayName("미획득 뱃지는 earned false 에 earnedAt·featuredRank 가 null 이고 isNew 는 false 다 — §D2 null 규칙")
	void 미획득_뱃지는_earned_false에_earnedAt과_featuredRank가_null이고_isNew는_false다() {
		List<MyBadgeResponseDto> result = badgeQueryService.findMyBadges(me);

		// 아무것도 획득하지 않은 신규 유저 — 전 행이 미획득 규칙을 따라야 한다. isNew 3값(null) 금지.
		assertThat(result).isNotEmpty().allSatisfy(row -> {
			assertThat(row.earned()).isFalse();
			assertThat(row.earnedAt()).isNull();
			assertThat(row.featuredRank()).isNull();
			assertThat(row.isNew()).isFalse();
		});
	}

	// 검증: FR-BADGE-07
	@Test
	@DisplayName("획득 뱃지는 earnedAt 이 포함된다 — 진열장 파생 키(§D4)")
	void 획득_뱃지는_earnedAt이_포함된다() {
		grantRetro(me, explorer1Id);

		List<MyBadgeResponseDto> result = badgeQueryService.findMyBadges(me);

		MyBadgeResponseDto earned = rowOf(result, "EXPLORER_1");
		assertThat(earned.earned()).isTrue();
		assertThat(earned.earnedAt()).isNotNull();
	}

	// 검증: FR-BADGE-07, FR-BADGE-08
	@Test
	@DisplayName("미확인 뱃지는 첫 조회에서 isNew true 로 표시된다 — SELECT 시점 값(§D3)")
	void 미확인_뱃지는_첫_조회에서_isNew_true로_표시된다() {
		grantRetro(me, explorer1Id);

		List<MyBadgeResponseDto> result = badgeQueryService.findMyBadges(me);

		assertThat(rowOf(result, "EXPLORER_1").isNew()).isTrue();
	}

	// 검증: FR-BADGE-08
	@Test
	@DisplayName("조회 후 미확인 뱃지의 notified_at 이 기록된다 — 자동 스탬프(§D3)")
	void 조회_후_미확인_뱃지의_notified_at이_기록된다() {
		grantRetro(me, explorer1Id);

		badgeQueryService.findMyBadges(me);

		assertThat(notifiedAt(me, explorer1Id)).isNotNull();
	}

	// 검증: FR-BADGE-08
	@Test
	@DisplayName("두 번째 조회부터 isNew 는 false 다 — §D3")
	void 두번째_조회부터_isNew는_false다() {
		grantRetro(me, explorer1Id);
		badgeQueryService.findMyBadges(me);

		List<MyBadgeResponseDto> second = badgeQueryService.findMyBadges(me);

		assertThat(rowOf(second, "EXPLORER_1").isNew()).isFalse();
	}

	// 검증: FR-BADGE-08
	@Test
	@DisplayName("동기 지급분은 첫 조회부터 isNew 가 false 다 — 업로드 응답에 이미 노출(MSG-239 §D8)")
	void 동기_지급분은_첫_조회부터_isNew가_false다() {
		userBadgeRepository.insertIgnoreConflict(me, explorer1Id);

		List<MyBadgeResponseDto> result = badgeQueryService.findMyBadges(me);

		MyBadgeResponseDto earned = rowOf(result, "EXPLORER_1");
		assertThat(earned.earned()).isTrue();
		assertThat(earned.isNew()).isFalse();
	}

	// 검증: FR-BADGE-08
	@Test
	@DisplayName("스탬프는 조회 응답에 노출된 뱃지에만 적용된다 — IN 리스트 한정(§D3)")
	void 스탬프는_조회_응답에_노출된_뱃지에만_적용된다() {
		// "SELECT 이후 삽입된 소급 행은 스탬프되지 않는다"의 검증 대체 — 미확인 2건 중 1건 id 로만 직접 호출.
		grantRetro(me, explorer1Id);
		grantRetro(me, explorer10Id);

		int updated = userBadgeRepository.markMyBadgesNotified(me, List.of(explorer1Id));

		assertThat(updated).isEqualTo(1);
		assertThat(notifiedAt(me, explorer1Id)).isNotNull();
		assertThat(notifiedAt(me, explorer10Id)).isNull();
	}

	// 검증: FR-BADGE-07
	@Test
	@DisplayName("대표 뱃지는 featuredRank 로 노출된다 — 별도 API 없음(§D5)")
	void 대표_뱃지는_featuredRank로_노출된다() {
		grantRetro(me, explorer1Id);
		grantRetro(me, explorer10Id);
		setFeatured(me, explorer10Id, 1);
		setFeatured(me, explorer1Id, 2);

		List<MyBadgeResponseDto> result = badgeQueryService.findMyBadges(me);

		assertThat(rowOf(result, "EXPLORER_10").featuredRank()).isEqualTo(1);
		assertThat(rowOf(result, "EXPLORER_1").featuredRank()).isEqualTo(2);
		assertThat(result).filteredOn(row -> row.featuredRank() != null).hasSize(2);
	}

	// 검증: FR-BADGE-07, FR-BADGE-08
	@Test
	@DisplayName("조회는 타인의 획득 상태와 미확인 플래그에 영향을 주지 않는다 — userId 스코프(§성공 기준 6)")
	void 조회는_타인의_획득_상태와_미확인_플래그에_영향을_주지_않는다() {
		grantRetro(other, explorer1Id);

		List<MyBadgeResponseDto> result = badgeQueryService.findMyBadges(me);

		// 타인만 획득한 뱃지는 내 목록에서 미획득이고, 내 조회가 타인의 미확인 플래그를 지우지 않는다.
		assertThat(rowOf(result, "EXPLORER_1").earned()).isFalse();
		assertThat(notifiedAt(other, explorer1Id)).isNull();
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "뱃지조회테스터")).getId();
	}

	private long badgeId(String code) {
		return ((Number) em.createNativeQuery("SELECT id FROM badges WHERE code = :code")
			.setParameter("code", code)
			.getSingleResult()).longValue();
	}

	/** 소급 지급과 같은 형태의 미확인 지급 — notified_at NULL(V9 소급 블록과 동일 모양). */
	private void grantRetro(long userId, long badgeId) {
		em.createNativeQuery("INSERT INTO user_badges (user_id, badge_id) VALUES (:userId, :badgeId)")
			.setParameter("userId", userId)
			.setParameter("badgeId", badgeId)
			.executeUpdate();
	}

	private void setFeatured(long userId, long badgeId, int rank) {
		em.createNativeQuery("""
				UPDATE user_badges SET featured_rank = :rank
				WHERE user_id = :userId AND badge_id = :badgeId
				""")
			.setParameter("rank", rank)
			.setParameter("userId", userId)
			.setParameter("badgeId", badgeId)
			.executeUpdate();
	}

	private Object notifiedAt(long userId, long badgeId) {
		return em.createNativeQuery("""
				SELECT notified_at FROM user_badges
				WHERE user_id = :userId AND badge_id = :badgeId
				""")
			.setParameter("userId", userId)
			.setParameter("badgeId", badgeId)
			.getSingleResult();
	}

	private MyBadgeResponseDto rowOf(List<MyBadgeResponseDto> rows, String code) {
		return rows.stream().filter(row -> row.code().equals(code)).findFirst().orElseThrow();
	}
}
