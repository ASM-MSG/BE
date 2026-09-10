package com.msg.fillmap.event.submission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 신청 위치의 제출 원본 사각형 한 개 (MSG-498). 격자 인덱스 정수 넷이고 형식은 seed/events.json 과 같다.
 * 저장하는 이유는 재제출 폼 프리필과 관리자 검토 재료라 "사용자가 그린 그대로"가 필요해서다 —
 * 판정(81칸 상한·대표 격자)은 전개한 격자 집합으로 하지 이 표현으로 하지 않는다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSubmissionAreaRect {

	@Column(name = "min_grid_y", nullable = false)
	private Integer minGridY;

	@Column(name = "max_grid_y", nullable = false)
	private Integer maxGridY;

	@Column(name = "min_grid_x", nullable = false)
	private Integer minGridX;

	@Column(name = "max_grid_x", nullable = false)
	private Integer maxGridX;

	public EventSubmissionAreaRect(Integer minGridY, Integer maxGridY, Integer minGridX, Integer maxGridX) {
		this.minGridY = minGridY;
		this.maxGridY = maxGridY;
		this.minGridX = minGridX;
		this.maxGridX = maxGridX;
	}
}
