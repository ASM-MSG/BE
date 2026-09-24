package com.msg.fillmap.notification.entity;

import java.util.Objects;

/**
 * 알림 딥링크 이동 대상 (MSG-432 D-2) — 종류와 식별자 한 쌍. 발생 지점은 팩토리만 부르므로 종류와
 * 식별자가 어긋날 자리가 없고, 생성자가 null 을 거부해 "종류만 있는 대상"은 코드에서 만들어지지 않는다
 * (DB 는 chk_notifications_target_pair 가 최종 방어). 식별자가 문자열인 이유는 격자 id 가 문자열이라서다.
 */
public record NotificationTarget(NotificationTargetType type, String id) {

	public NotificationTarget {
		Objects.requireNonNull(type, "target type");
		Objects.requireNonNull(id, "target id");
	}

	public static NotificationTarget video(long videoId) {
		return new NotificationTarget(NotificationTargetType.VIDEO, Long.toString(videoId));
	}

	public static NotificationTarget grid(String gridId) {
		return new NotificationTarget(NotificationTargetType.GRID, gridId);
	}

	public static NotificationTarget badge(long badgeId) {
		return new NotificationTarget(NotificationTargetType.BADGE, Long.toString(badgeId));
	}

	public static NotificationTarget eventOccurrence(long occurrenceId) {
		return new NotificationTarget(NotificationTargetType.EVENT_OCCURRENCE, Long.toString(occurrenceId));
	}

	public static NotificationTarget user(long userId) {
		return new NotificationTarget(NotificationTargetType.USER, Long.toString(userId));
	}
}
