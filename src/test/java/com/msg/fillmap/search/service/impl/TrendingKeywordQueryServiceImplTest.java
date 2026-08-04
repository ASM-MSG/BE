package com.msg.fillmap.search.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.search.dto.TrendingKeywordResponseDto;
import com.msg.fillmap.search.repository.SearchKeywordDailyCountRepository;

/**
 * 인기 검색어 조회 통합 테스트 (MSG-258) — 실 PostgreSQL 집계 쿼리 + 고정 Clock(KST 날짜 판정).
 * 고정 시각을 2000-01-01 로 잡아 실데이터(현재 날짜) 행과 섞이지 않게 하고, 공유 로컬 DB 라
 * 검색어는 UUID 로 고유화 · @Transactional 롤백으로 격리한다.
 */
@SpringBootTest
@Transactional
@DisplayName("TrendingKeywordQueryServiceImpl 오늘+어제 TOP 10")
class TrendingKeywordQueryServiceImplTest {

	/** 2000-01-01T14:00:00Z = KST 2000-01-01 23:00 — 오늘(2000-01-01)·어제(1999-12-31) 판정. */
	private static final Instant FIXED_INSTANT = Instant.parse("2000-01-01T14:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2000, 1, 1);
	private static final LocalDate YESTERDAY = LocalDate.of(1999, 12, 31);

	@Autowired
	private SearchKeywordDailyCountRepository searchKeywordDailyCountRepository;

	private TrendingKeywordQueryServiceImpl trendingKeywordQueryService;

	@BeforeEach
	void setUp() {
		trendingKeywordQueryService = new TrendingKeywordQueryServiceImpl(searchKeywordDailyCountRepository,
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	private void count(LocalDate date, String keyword, int times) {
		for (int i = 0; i < times; i++) {
			searchKeywordDailyCountRepository.upsertIncrement(date, keyword);
		}
	}

	@Test
	void 오늘과_어제_합산_상위_10개가_순위와_검색어로_반환된다() {
		String prefix = UUID.randomUUID().toString();
		count(TODAY, prefix + "-소수", 1);
		count(TODAY, prefix + "-다수", 2);
		count(YESTERDAY, prefix + "-다수", 3);   // 합산 5 — 오늘만 보면 2위지만 합산은 1위

		List<TrendingKeywordResponseDto> top = trendingKeywordQueryService.findTop10();

		assertThat(top).containsExactly(
			new TrendingKeywordResponseDto(1, prefix + "-다수"),
			new TrendingKeywordResponseDto(2, prefix + "-소수"));
	}

	@Test
	void 그제_카운트는_순위에_반영되지_않는다() {
		count(YESTERDAY.minusDays(1), UUID.randomUUID() + "-그제", 5);

		assertThat(trendingKeywordQueryService.findTop10()).isEmpty();
	}

	@Test
	void 집계가_없으면_빈_목록을_반환한다() {
		assertThat(trendingKeywordQueryService.findTop10()).isEmpty();
	}

	@Test
	void 동률이면_키워드_사전순으로_순위가_결정된다() {
		String prefix = UUID.randomUUID().toString();
		count(TODAY, prefix + "-b", 2);
		count(YESTERDAY, prefix + "-a", 2);

		List<TrendingKeywordResponseDto> top = trendingKeywordQueryService.findTop10();

		assertThat(top).extracting(TrendingKeywordResponseDto::keyword)
			.containsExactly(prefix + "-a", prefix + "-b");
	}

	@Test
	void 순위는_1부터_빈틈없이_부여된다() {
		String prefix = UUID.randomUUID().toString();
		for (int i = 0; i < 11; i++) {
			count(TODAY, prefix + "-" + i, 11 - i);
		}

		List<TrendingKeywordResponseDto> top = trendingKeywordQueryService.findTop10();

		assertThat(top).hasSize(10);
		assertThat(top).extracting(TrendingKeywordResponseDto::rank)
			.containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
	}
}
