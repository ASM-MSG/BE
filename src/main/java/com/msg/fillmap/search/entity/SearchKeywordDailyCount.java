package com.msg.fillmap.search.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 검색어 일별 집계 row (search_keyword_daily_counts — MSG-258). (KST 일자, 정규화 검색어) 당 1행이며
 * 사용자 식별자를 담지 않는다(전역 집계 — FR-7 약관 경계). 쓰기는 전부
 * SearchKeywordDailyCountRepository.upsertIncrement 의 native UPSERT 한 문장이라 읽기 전용 매핑이다(§D5).
 */
@Entity
@Table(name = "search_keyword_daily_counts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchKeywordDailyCount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** KST(Asia/Seoul) 기준 일자 — 애플리케이션이 확정해 전달한다(DB TIMESTAMP 는 UTC 저장 관례). */
	@Column(name = "keyword_date", nullable = false)
	private LocalDate keywordDate;

	@Column(nullable = false, length = 255)
	private String keyword;

	@Column(name = "search_count", nullable = false)
	private int searchCount;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
