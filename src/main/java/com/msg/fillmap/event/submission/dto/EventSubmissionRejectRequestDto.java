package com.msg.fillmap.event.submission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * 반려 요청 (MSG-500 §API 4). 항목 코드와 자유 서술이 <b>둘 다</b> 필수다 — 화면이 항목 카드와 본문을
 * 함께 그리고, "반려 행에는 사유 두 벌이 있다"는 DDL CHECK 도 그 형태를 요구한다.
 *
 * <p>코드 목록에 Bean Validation 을 걸지 않는 것은 의도다: 빈 배열·허용 밖 값·중복을 전부 도메인 코드
 * 13454 로 내야 하는데, {@code @NotEmpty} 나 enum 역직렬화 실패에 맡기면 공통 400 으로 뭉개진다.
 * 검증은 서비스가 한다.
 */
@Schema(description = "행사 등재 신청 반려 요청", requiredProperties = {"reasonCodes", "reasonText"})
public record EventSubmissionRejectRequestDto(
	@Schema(description = "반려 항목 코드 1개 이상 (PERIOD, AREA, IMAGE, INFO — 중복 불가)",
		example = "[\"AREA\", \"INFO\"]")
	List<String> reasonCodes,

	@Schema(description = "반려 사유 본문", example = "신청 영역이 행사 실제 범위보다 넓습니다")
	@NotBlank String reasonText
) {
}
