package com.msg.fillmap.notification.sender;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;

/**
 * FCM 발송 구현 (MSG-179 D9). sendEachForMulticast 1콜(토큰 ≤500 — 사용자당 토큰 수 대비 충분).
 * 응답별 UNREGISTERED·INVALID_ARGUMENT 는 invalidTokens 로 분류하고(FR-5 삭제 대상), 그 외 실패는
 * 배치 호출 자체가 실패했을 때만 예외 — 부분 성공은 SENT 판정을 컨슈머에 맡긴다 (D5 단서).
 */
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
@RequiredArgsConstructor
public class FcmNotificationSender implements NotificationSender {

	private final FirebaseMessaging firebaseMessaging;

	@Override
	public SendResult send(List<String> tokens, String title, String body) {
		MulticastMessage message = MulticastMessage.builder()
			.addAllTokens(tokens)
			.setNotification(Notification.builder().setTitle(title).setBody(body).build())
			.build();
		BatchResponse response;
		try {
			response = firebaseMessaging.sendEachForMulticast(message);
		} catch (FirebaseMessagingException e) {
			// 배치 호출 전체 실패 = 전송 실패 — 컨슈머가 던진 그대로 재시도 경로(D4)를 탄다.
			throw new IllegalStateException("FCM multicast 호출 실패", e);
		}
		List<String> invalidTokens = new ArrayList<>();
		List<SendResponse> responses = response.getResponses();
		for (int i = 0; i < responses.size(); i++) {
			SendResponse sendResponse = responses.get(i);
			if (sendResponse.isSuccessful()) {
				continue;
			}
			MessagingErrorCode code = sendResponse.getException().getMessagingErrorCode();
			if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
				invalidTokens.add(tokens.get(i));
			}
		}
		return new SendResult(response.getSuccessCount(), invalidTokens);
	}
}
