package com.msg.fillmap.search.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.search.repository.SearchKeywordDailyCountRepository;

/**
 * 집계 워커 경로의 트랜잭션 검증 (MSG-258 §D9). 워커 스레드에는 서비스 레벨 트랜잭션 문맥이 없으므로
 * @Modifying native UPSERT 는 리포지토리 메서드 자신이 트랜잭션을 열어야 한다 — 테스트 클래스에
 * @Transactional 을 걸지 않아 "주변 트랜잭션 없음" 조건을 그대로 재현한다. 커밋된 행은 롤백되지 않으니
 * 공유 로컬 DB 오염을 막기 위해 UUID 검색어로 고유화하고 @AfterEach 에서 직접 지운다.
 */
@SpringBootTest
@DisplayName("검색어 집계 — 주변 트랜잭션 없는 UPSERT 커밋 (§D9)")
class SearchKeywordCommandTransactionTest {

	/** 2000-01-01T14:00:00Z = KST 2000-01-01 23:00 — 실서비스 날짜·키와 격리. */
	private static final Instant FIXED_INSTANT = Instant.parse("2000-01-01T14:00:00Z");
	private static final LocalDate KST_DATE = LocalDate.of(2000, 1, 1);
	private static final String DEDUPE_KEY = "searchdedupe:20000101";
	private static final long USER_ID = 42L;
	private static final String SEARCHER_KEY = String.valueOf(USER_ID);

	private final String keyword = "테스트-" + UUID.randomUUID();

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private SearchKeywordDailyCountRepository searchKeywordDailyCountRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManager em;

	@AfterEach
	void tearDown() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM search_keyword_daily_counts WHERE keyword = :keyword")
				.setParameter("keyword", keyword)
				.executeUpdate());
		redisTemplate.opsForSet().remove(DEDUPE_KEY, USER_ID + ":" + keyword);
	}

	// 검증: FR-SEARCH-05
	@Test
	void 주변_트랜잭션이_없어도_집계_카운트가_커밋된다() {
		SearchKeywordCommandServiceImpl service = new SearchKeywordCommandServiceImpl(redisTemplate,
			searchKeywordDailyCountRepository, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), Runnable::run);

		service.recordSearch(SEARCHER_KEY, keyword);

		// 조회는 별개 커넥션·트랜잭션 밖 — 보인다면 UPSERT 가 스스로 트랜잭션을 열고 커밋했다는 뜻
		assertThat(searchKeywordDailyCountRepository.findTopKeywords(KST_DATE, KST_DATE.minusDays(1)))
			.contains(keyword);
	}
}
