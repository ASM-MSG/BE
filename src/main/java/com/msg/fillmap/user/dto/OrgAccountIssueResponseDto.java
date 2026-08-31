package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 계정 발급 결과 (MSG-499 API 4·6). <b>초기 비밀번호 평문은 실리지 않는다</b> — 평문은 메일 본문에만
 * 한 번 나가고 응답·로그·DB 어디에도 남지 않는다(FR-2).
 *
 * <p>{@code emailSent} 가 false 여도 계정은 발급된 것이다. 복구는 재발송 API 이고, 응답 자체를 못 받은
 * 경우(커밋과 발송 사이의 크래시)에는 요청 상세를 재조회해 ISSUED 면 재발송을 쓴다.
 */
@Schema(description = "계정 발급 결과", requiredProperties = {"userId", "emailSent"})
public record OrgAccountIssueResponseDto(
	@Schema(description = "발급된 계정 id", example = "42")
	Long userId,

	@Schema(description = "초기 비밀번호 메일 발송 성공 여부. true 는 SES 접수까지의 성공이고 배달 확인은 아니다", example = "true")
	boolean emailSent
) {
}
