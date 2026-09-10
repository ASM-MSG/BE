package com.msg.fillmap.streak.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스트릭 row (streaks, 1 user = 1 row — MSG-200). 매일 영상을 업로드한 날이 이어진 연속 일수를
 * 기록한다(glossary "스트릭" — 재방문 업로드도 인정, KST 자정 경계). 쓰기는 전부
 * StreakRepository.upsertOnUpload 의 native UPSERT 한 문장이라 읽기 전용 매핑이다(§D8) —
 * Setter·팩토리 없음, 조회 노출(도감 summary)은 별도 티켓 소관.
 */
@Entity
@Table(name = "streaks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Streak {

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "current_count", nullable = false)
	private int currentCount;

	@Column(name = "max_count", nullable = false)
	private int maxCount;

	@Column(name = "last_recorded_date")
	private LocalDate lastRecordedDate;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
