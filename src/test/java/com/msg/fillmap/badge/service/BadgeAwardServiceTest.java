package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;

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

/**
 * award 의 "INSERT 성공분만 반환" 필터 단위 검증 (Codex 교차 리뷰 반영). 동시 요청 경합에서 진
 * 트랜잭션(insertIgnoreConflict 가 conflict 로 0 반환)은 후보였더라도 newBadges 에서 빠져야 한다 —
 * 경합 타이밍(후보 조회와 INSERT 사이의 상대 커밋)은 통합 테스트로 결정적 재현이 안 돼 mock 으로 가른다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeAwardService 지급 성공분만 반환")
class BadgeAwardServiceTest {

	private static final long USER_ID = 42L;

	@Mock
	private BadgeRepository badgeRepository;

	@Mock
	private UserBadgeRepository userBadgeRepository;

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
