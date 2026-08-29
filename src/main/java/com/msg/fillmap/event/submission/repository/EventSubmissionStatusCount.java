package com.msg.fillmap.event.submission.repository;

import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;

/** 내 신청의 상태별 건수 한 줄 (MSG-498 FR-11). 건수가 0인 상태는 행 자체가 없다. */
public record EventSubmissionStatusCount(EventSubmissionStatus status, Long count) {
}
