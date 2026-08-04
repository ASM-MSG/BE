package com.msg.fillmap.search.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.search.entity.SearchKeywordDailyCount;

/**
 * 검색어 일별 집계 리포지토리 (MSG-258). 쓰기는 native UPSERT 한 문장, 읽기는 오늘+어제 합산 TOP 10
 * 한 문장이 전부다. 날짜(KST)는 애플리케이션이 확정해 파라미터로 넘긴다 — dedupe 키 날짜와 같은 값이라
 * 자정 경계 불일치가 없다(§D5).
 */
public interface SearchKeywordDailyCountRepository extends JpaRepository<SearchKeywordDailyCount, Long> {

	/**
	 * (날짜, 검색어) 카운트 +1 — 행이 없으면 1 로 생성한다. 워커 스레드가 호출하는 경로에는 서비스 레벨
	 * 트랜잭션 문맥이 없어 메서드 자체가 단문 트랜잭션을 연다(§D9). ON CONFLICT 행 잠금이 동시 증가를
	 * 직렬화하므로 카운트 유실이 없다.
	 */
	@Transactional
	@Modifying
	@Query(value = """
		INSERT INTO search_keyword_daily_counts (keyword_date, keyword, search_count)
		VALUES (:date, :keyword, 1)
		ON CONFLICT (keyword_date, keyword)
		DO UPDATE SET search_count = search_keyword_daily_counts.search_count + 1
		""", nativeQuery = true)
	int upsertIncrement(@Param("date") LocalDate date, @Param("keyword") String keyword);

	/** 두 날짜(오늘·어제) 합산 상위 10개 검색어 — 동률은 keyword 사전순으로 항상 하나로 결정된다(§D6). */
	@Query(value = """
		SELECT keyword
		FROM search_keyword_daily_counts
		WHERE keyword_date IN (:today, :yesterday)
		GROUP BY keyword
		ORDER BY SUM(search_count) DESC, keyword ASC
		LIMIT 10
		""", nativeQuery = true)
	List<String> findTopKeywords(@Param("today") LocalDate today, @Param("yesterday") LocalDate yesterday);
}
