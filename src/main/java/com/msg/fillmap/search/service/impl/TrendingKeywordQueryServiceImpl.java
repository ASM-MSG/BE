package com.msg.fillmap.search.service.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.msg.fillmap.search.dto.TrendingKeywordResponseDto;
import com.msg.fillmap.search.repository.SearchKeywordDailyCountRepository;
import com.msg.fillmap.search.service.TrendingKeywordQueryService;

/**
 * 인기 검색어 조회 구현 (MSG-258). 오늘·어제(KST) 두 날짜를 넘겨 집계 쿼리 1회, 결과 순서대로 rank 1..N 을
 * 부여한다(§D6). 캐시 없음 — 2일치 소량 테이블의 단순 GROUP BY 라 느려지면 그때 붙인다.
 */
@Service
public class TrendingKeywordQueryServiceImpl implements TrendingKeywordQueryService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final SearchKeywordDailyCountRepository searchKeywordDailyCountRepository;
	private final Clock clock;

	@Autowired
	public TrendingKeywordQueryServiceImpl(SearchKeywordDailyCountRepository searchKeywordDailyCountRepository) {
		// systemUTC + KST 존 변환: 기본존에 의존하면 서버 타임존 설정에 따라 날짜 경계가 흔들린다
		this(searchKeywordDailyCountRepository, Clock.systemUTC());
	}

	/** 오늘·어제 경계 검증용 고정 Clock 주입 (§테스트 시나리오 조회). */
	public TrendingKeywordQueryServiceImpl(SearchKeywordDailyCountRepository searchKeywordDailyCountRepository,
		Clock clock) {
		this.searchKeywordDailyCountRepository = searchKeywordDailyCountRepository;
		this.clock = clock;
	}

	@Override
	public List<TrendingKeywordResponseDto> findTop10() {
		LocalDate today = LocalDate.now(clock.withZone(KST));
		List<String> keywords = searchKeywordDailyCountRepository.findTopKeywords(today, today.minusDays(1));
		return IntStream.range(0, keywords.size())
			.mapToObj(index -> new TrendingKeywordResponseDto(index + 1, keywords.get(index)))
			.toList();
	}
}
