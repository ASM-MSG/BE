package com.msg.fillmap.notification.sender;

import java.util.List;

import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.entity.NotificationTarget;

/**
 * 발송기 추상화 (MSG-179 D9, PRD §6 "발송기는 인터페이스 뒤"). 테스트는 이 인터페이스를 목으로 갈아끼워
 * firebase-admin 실호출 없이 컨슈머 흐름을 검증한다 (D12).
 */
public interface NotificationSender {

	/**
	 * 토큰들로 발송. 성공 수와 무효 토큰(FR-5 삭제 대상) 목록을 돌려준다. 전송 실패는 예외.
	 * category 와 target 은 앱 탭 라우팅용 data 페이로드 재료다 (MSG-432 FR-2) — target 은 null 허용(대상 없음).
	 */
	SendResult send(long notificationId, List<String> tokens, String title, String body,
		NotificationCategory category, NotificationTarget target);

	record SendResult(int successCount, List<String> invalidTokens) {
	}
}
