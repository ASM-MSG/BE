package com.msg.fillmap.friend.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.GridColor;
import com.msg.fillmap.usergrid.dto.CollectionSummaryResponseDto;

/**
 * 친구 프로필 + 도감 요약 + 최근 수집 격자 (MSG-186 D1). 화면 하나가 소비하므로 한 응답에 담는다.
 * summary 는 본인 도감 요약과 같은 DTO·같은 산식이다 — 같은 사용자를 본인이 볼 때와 친구가 볼 때 숫자가
 * 달라지지 않아야 해서 복제하지 않고 중첩 재사용한다. 수집 0건 친구는 summary 3필드 0 + 빈 recentGrids.
 */
@Schema(description = "친구 프로필 — 프로필 정보와 도감 요약·최근 수집 격자.",
	requiredProperties = {"nickname", "gridColor", "summary", "recentGrids", "profileImageUrl"})
public record FriendProfileResponseDto(
	@Schema(description = "친구의 닉네임", example = "채우미")
	String nickname,

	@Schema(description = "친구의 프로필 이미지 URL — 미설정이면 null", nullable = true)
	String profileImageUrl,

	@Schema(description = "친구의 도감 색상", example = "PINK")
	GridColor gridColor,

	@Schema(description = "친구의 도감 요약 — 본인이 보는 값과 동일하다")
	CollectionSummaryResponseDto summary,

	@Schema(description = "친구가 최근 수집한 격자 최대 30개 — 수집 시각 역순")
	List<FriendCollectionGridResponseDto> recentGrids
) {
}
