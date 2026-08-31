package com.msg.fillmap.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;

import com.msg.fillmap.user.entity.User;

/**
 * 발급된 행사 운영자 계정 목록 (MSG-499 API 8). 재발송 대상 식별과 직접 발급의 크래시 복구 확인에 쓴다.
 * 목록 대상은 이 티켓의 발급 경로가 만드는 형태(role ORG · provider LOCAL)로 좁혀져 있다.
 */
@Schema(
	description = "발급된 행사 운영자 계정 목록 — 발급 최신순 한 페이지.",
	requiredProperties = {"totalElements", "page", "size", "accounts"}
)
public record AdminOrgAccountListResponseDto(
	@Schema(description = "조건에 해당하는 전체 계정 수", example = "12")
	long totalElements,

	@Schema(description = "현재 페이지 번호 (0부터)", example = "0")
	int page,

	@Schema(description = "페이지 크기", example = "20")
	int size,

	@Schema(description = "이 페이지의 계정 목록. 정렬은 발급 최신순 고정")
	List<AdminOrgAccountItemResponseDto> accounts
) {

	public static AdminOrgAccountListResponseDto from(Page<User> page) {
		return new AdminOrgAccountListResponseDto(
			page.getTotalElements(),
			page.getNumber(),
			page.getSize(),
			page.getContent().stream().map(AdminOrgAccountItemResponseDto::from).toList());
	}
}
