package com.msg.fillmap.notification.dto;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.notification.entity.Notification;
import com.msg.fillmap.notification.entity.NotificationCategory;

/**
 * 알림함 목록 응답 (GET /api/notifications — MSG-434 FR-1·FR-2). id 내림차순 keyset 페이지로,
 * 커서는 마지막 항목의 notificationId 하나다 (D-3 — 성분이 하나라 Base64 토큰 포장이 불요하다).
 * 발송 내부 상태(status·retry_count·last_error)와 내부 키(event_key)는 싣지 않는다 (비기능 보안).
 */
@Schema(description = "알림함 목록 한 페이지 — 최신순(id 내림차순)",
	requiredProperties = {"notifications", "nextCursor", "hasNext"})
public record NotificationPageResponseDto(
	@Schema(description = "알림 항목 — 없으면 빈 배열")
	List<NotificationItemResponseDto> notifications,

	@Schema(description = "다음 페이지 커서 — 다음 요청의 cursor 로 그대로 되돌려 준다. hasNext 가 false 면 null",
		example = "123", nullable = true)
	Long nextCursor,

	@Schema(description = "다음 페이지 존재 여부", example = "true")
	boolean hasNext
) {

	@Schema(description = "알림 한 건", requiredProperties = {"notificationId", "category", "title", "body",
		"createdAt", "read"})
	public record NotificationItemResponseDto(
		@Schema(description = "알림 ID — 읽음 처리와 커서에 쓴다", example = "123")
		long notificationId,

		// MISSION_NEARBY 는 서버 발송 경로가 없어(V36 주석의 CHECK 방어선) 여기에 등장하지 않는다 (FR-9).
		@Schema(description = "알림 카테고리", example = "BADGE",
			allowableValues = {"BADGE", "HOTZONE", "REMIND", "VIDEO", "WEEKLY", "FRIEND", "MODERATION"})
		NotificationCategory category,

		@Schema(description = "알림 제목", example = "새 뱃지 획득")
		String title,

		@Schema(description = "알림 본문", example = "'첫 걸음' 뱃지를 획득했어요")
		String body,

		@Schema(description = "생성 시각 (UTC)", example = "2026-08-19T02:11:00Z")
		LocalDateTime createdAt,

		@Schema(description = "읽음 여부", example = "false")
		boolean read
	) {

		public static NotificationItemResponseDto from(Notification notification) {
			return new NotificationItemResponseDto(notification.getId(), notification.getCategory(),
				notification.getTitle(), notification.getBody(), notification.getCreatedAt(),
				notification.getReadAt() != null);
		}
	}
}
