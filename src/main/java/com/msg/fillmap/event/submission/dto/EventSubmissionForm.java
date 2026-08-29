package com.msg.fillmap.event.submission.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 제출과 재제출이 공유하는 신청 폼 (MSG-498). 재제출이 부분 수정이 아니라 전체 교체라 두 요청의 내용이
 * 같고, 다른 것은 유형뿐이다(제출에만 있고 재제출에서는 바꿀 수 없다, D-8). 검증과 저장이 두 벌로
 * 갈라지지 않게 서비스는 이 타입 하나만 본다.
 */
public interface EventSubmissionForm {

	String title();

	String organizerName();

	LocalDate startsOn();

	LocalDate endsOn();

	/** POPUP 전용 필수. FESTIVAL 에 실려 오면 13439 다. */
	String operatingHours();

	/** FESTIVAL 전용 필수. POPUP 에 실려 오면 13439 다. */
	String programDescription();

	String description();

	/** pending 키. 재제출에서만 null 이 허용되고 그 뜻은 "기존 이미지 유지"다. */
	String imageS3Key();

	List<EventSubmissionLocationRequestDto> locations();
}
