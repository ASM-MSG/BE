package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.badge.repository.BadgeRepository;
import com.msg.fillmap.badge.repository.EligibleBadgeProjection;
import com.msg.fillmap.badge.repository.UserBadgeRepository;
import com.msg.fillmap.notification.service.NotificationCommandService;

/**
 * award 의 "INSERT 성공분만 반환" 필터와 행동 단위 메서드(awardUploadBadges·awardCollectionBadges)의
 * metric 산출·축 라우팅 단위 검증. 경합 타이밍(후보 조회와 INSERT 사이의 상대 커밋)은 통합 테스트로
 * 결정적 재현이 안 돼 mock 으로 가른다 — 진 트랜잭션(insertIgnoreConflict 0)은 newBadges 에서 빠진다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeAwardService 지급 성공분만 반환")
class BadgeAwardServiceTest {

	private static final long USER_ID = 42L;

	@Mock
	private BadgeRepository badgeRepository;

	@Mock
	private UserBadgeRepository userBadgeRepository;

	@Mock
	private NotificationCommandService notificationCommandService;

	@InjectMocks
	private BadgeAwardServiceImpl badgeAwardService;

	@Test
	@DisplayName("경합에서 진 후보(INSERT 영향 행 0)는 응답에 실리지 않는다")
	void 경합에서_진_후보는_응답에_실리지_않는다() {
		given(badgeRepository.findEligible(eq(BadgeConditionType.TOTAL_GRIDS.name()), any(), eq(USER_ID)))
			.willReturn(List.of(candidate(1L, "EXPLORER_1"), candidate(2L, "EXPLORER_10")));
		// 후보 조회 후 상대 트랜잭션이 EXPLORER_1 을 먼저 지급한 경합 — 내 INSERT 는 0 을 받는다.
		given(userBadgeRepository.insertIgnoreConflict(USER_ID, 1L)).willReturn(0);
		given(userBadgeRepository.insertIgnoreConflict(USER_ID, 2L)).willReturn(1);

		List<EarnedBadgeResponseDto> earned =
			badgeAwardService.award(USER_ID, BadgeConditionType.TOTAL_GRIDS, BigDecimal.TEN);

		assertThat(earned).extracting(EarnedBadgeResponseDto::code).containsExactly("EXPLORER_10");
	}

	@Test
	@DisplayName("후보가 없으면 INSERT 없이 빈 리스트를 반환한다")
	void 후보가_없으면_INSERT_없이_빈_리스트를_반환한다() {
		given(badgeRepository.findEligible(any(), any(), anyLong())).willReturn(List.of());

		assertThat(badgeAwardService.award(USER_ID, BadgeConditionType.UPLOAD_COUNT, BigDecimal.ONE)).isEmpty();
	}

	@Test
	@DisplayName("awardUploadBadges 는 생애 업로드 수를 세어 업로드 축만 판정한다")
	void awardUploadBadges는_생애_업로드_수를_세어_업로드_축만_판정한다() {
		given(userBadgeRepository.countMyVideos(USER_ID)).willReturn(BigDecimal.TEN);
		given(badgeRepository.findEligible(BadgeConditionType.UPLOAD_COUNT.name(), BigDecimal.TEN, USER_ID))
			.willReturn(List.of(candidate(6L, "RECORDER_10")));
		given(userBadgeRepository.insertIgnoreConflict(USER_ID, 6L)).willReturn(1);

		List<EarnedBadgeResponseDto> earned = badgeAwardService.awardUploadBadges(USER_ID);

		assertThat(earned).extracting(EarnedBadgeResponseDto::code).containsExactly("RECORDER_10");
	}

	@Test
	@DisplayName("awardCollectionBadges 는 총 격자 수와 수집률 두 축을 합산 반환한다")
	void awardCollectionBadges는_총_격자_수와_수집률_두_축을_합산_반환한다() {
		given(userBadgeRepository.countMyGrids(USER_ID)).willReturn(BigDecimal.ONE);
		given(badgeRepository.findEligible(BadgeConditionType.TOTAL_GRIDS.name(), BigDecimal.ONE, USER_ID))
			.willReturn(List.of(candidate(1L, "EXPLORER_1")));
		BigDecimal rate = new BigDecimal("10.00");
		given(userBadgeRepository.findMyRegionProgress(USER_ID, "41642_110458")).willReturn(Optional.of(rate));
		given(badgeRepository.findEligible(BadgeConditionType.REGION_PERCENT.name(), rate, USER_ID))
			.willReturn(List.of(candidate(9L, "REGION_MASTER_10")));
		given(userBadgeRepository.insertIgnoreConflict(anyLong(), anyLong())).willReturn(1);

		List<EarnedBadgeResponseDto> earned = badgeAwardService.awardCollectionBadges(USER_ID, "41642_110458");

		assertThat(earned).extracting(EarnedBadgeResponseDto::code)
			.containsExactly("EXPLORER_1", "REGION_MASTER_10");
	}

	@Test
	@DisplayName("awardCollectionBadges 는 저장된 수집률이 없으면(무라벨 격자) 수집률 축을 건너뛴다")
	void awardCollectionBadges는_저장된_수집률이_없으면_수집률_축을_건너뛴다() {
		given(userBadgeRepository.countMyGrids(USER_ID)).willReturn(BigDecimal.ONE);
		given(badgeRepository.findEligible(BadgeConditionType.TOTAL_GRIDS.name(), BigDecimal.ONE, USER_ID))
			.willReturn(List.of(candidate(1L, "EXPLORER_1")));
		given(userBadgeRepository.findMyRegionProgress(USER_ID, "1_1")).willReturn(Optional.empty());
		given(userBadgeRepository.insertIgnoreConflict(USER_ID, 1L)).willReturn(1);

		List<EarnedBadgeResponseDto> earned = badgeAwardService.awardCollectionBadges(USER_ID, "1_1");

		assertThat(earned).extracting(EarnedBadgeResponseDto::code).containsExactly("EXPLORER_1");
		then(badgeRepository).should(never())
			.findEligible(eq(BadgeConditionType.REGION_PERCENT.name()), any(), anyLong());
	}

	private EligibleBadgeProjection candidate(long badgeId, String code) {
		return new EligibleBadgeProjection() {
			@Override
			public Long getBadgeId() {
				return badgeId;
			}

			@Override
			public String getCode() {
				return code;
			}

			@Override
			public String getName() {
				return code;
			}

			@Override
			public String getDescription() {
				return null;
			}

			@Override
			public String getIconUrl() {
				return null;
			}
		};
	}
}
