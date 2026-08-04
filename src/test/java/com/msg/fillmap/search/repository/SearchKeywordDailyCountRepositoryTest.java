package com.msg.fillmap.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * SearchKeywordDailyCountRepository·V22 스키마 통합 (실 PostgreSQL) — UPSERT 증가·날짜 분리.
 * 공유 로컬 DB 라 검색어는 UUID 로 고유화하고 @Transactional 롤백으로 격리한다.
 * 약관 경계(FR-7) 검증도 여기 — 테이블 컬럼에 사용자 식별자·카카오 응답 필드가 없어야 한다.
 * TOP 10 쿼리(합산·동률·LIMIT)는 같은 쿼리를 태우는 TrendingKeywordQueryServiceImplTest 소관.
 */
@SpringBootTest
@Transactional
@DisplayName("SearchKeywordDailyCountRepository·V22 스키마 (실 PostgreSQL)")
class SearchKeywordDailyCountRepositoryTest {

	/** 실데이터(현재 날짜)와 겹치지 않는 과거 날짜 — 조회 테스트가 남의 행을 주워 담지 않게. */
	private static final LocalDate DATE = LocalDate.of(2000, 1, 1);
	private static final LocalDate YESTERDAY = DATE.minusDays(1);

	@Autowired
	private SearchKeywordDailyCountRepository searchKeywordDailyCountRepository;

	@Autowired
	private EntityManager em;

	private String keyword() {
		return "테스트-" + UUID.randomUUID();
	}

	private int searchCount(LocalDate date, String keyword) {
		return ((Number) em.createNativeQuery(
				"SELECT search_count FROM search_keyword_daily_counts WHERE keyword_date = :date AND keyword = :keyword")
			.setParameter("date", date)
			.setParameter("keyword", keyword)
			.getSingleResult()).intValue();
	}

	@Test
	void 같은_날_같은_검색어는_행이_늘지_않고_카운트가_증가한다() {
		String keyword = keyword();

		searchKeywordDailyCountRepository.upsertIncrement(DATE, keyword);
		searchKeywordDailyCountRepository.upsertIncrement(DATE, keyword);
		searchKeywordDailyCountRepository.upsertIncrement(DATE, keyword);

		assertThat(searchCount(DATE, keyword)).isEqualTo(3);
	}

	@Test
	void 같은_검색어라도_날짜가_다르면_행이_분리된다() {
		String keyword = keyword();

		searchKeywordDailyCountRepository.upsertIncrement(DATE, keyword);
		searchKeywordDailyCountRepository.upsertIncrement(YESTERDAY, keyword);

		assertThat(searchCount(DATE, keyword)).isEqualTo(1);
		assertThat(searchCount(YESTERDAY, keyword)).isEqualTo(1);
	}

	@Test
	void 집계_저장소에는_정규화_검색어와_날짜와_횟수만_남는다() {
		// FR-7 — 사용자 식별자·카카오 응답 필드(장소명·주소·좌표)가 컬럼으로도 존재하지 않는다
		List<String> columns = ((List<?>) em.createNativeQuery(
				"SELECT column_name FROM information_schema.columns WHERE table_name = 'search_keyword_daily_counts'")
			.getResultList()).stream()
			.map(String::valueOf)
			.toList();

		assertThat(columns).containsExactlyInAnyOrder("id", "keyword_date", "keyword", "search_count", "created_at");
	}
}
