package com.msg.fillmap.notification.sender;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;

/**
 * FCM 발송 구현 (MSG-179 D9). sendEachForMulticast 는 토큰 500개 하드 리밋(초과 시 요청 자체 거부 →
 * 반복 실패 → 영구 DEAD)이라 500 단위 청크로 나눠 호출하고 결과를 합산한다. 응답별 UNREGISTERED·
 * INVALID_ARGUMENT 는 invalidTokens 로 분류하고(FR-5 삭제 대상), 청크 호출 실패는 성공 청크 결과를
 * 반영한 채 전체 실패(성공 0)일 때만 예외 — 부분 성공은 SENT 판정을 컨슈머에 맡긴다 (D5 단서).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
@RequiredArgsConstructor
public class FcmNotificationSender implements NotificationSender {

	private static final int MULTICAST_LIMIT = 500;   // FCM sendEachForMulticast 하드 리밋

	private final FirebaseMessaging firebaseMessaging;

	@Override
	public SendResult send(List<String> tokens, String title, String body) {
		int successCount = 0;
		List<String> invalidTokens = new ArrayList<>();
		IllegalStateException firstBatchFailure = null;
		for (int from = 0; from < tokens.size(); from += MULTICAST_LIMIT) {
			List<String> chunk = tokens.subList(from, Math.min(from + MULTICAST_LIMIT, tokens.size()));
			try {
				BatchResponse response = firebaseMessaging.sendEachForMulticast(buildMessage(chunk, title, body));
				successCount += response.getSuccessCount();
				collectInvalidTokens(response, chunk, invalidTokens);
			} catch (FirebaseMessagingException e) {
				log.warn("FCM multicast 청크 호출 실패 (청크 {}건) — 성공 청크 결과는 유지", chunk.size(), e);
				if (firstBatchFailure == null) {
					firstBatchFailure = new IllegalStateException("FCM multicast 호출 실패", e);
				}
			}
		}
		if (successCount == 0 && firstBatchFailure != null) {
			// 전체 실패 — 컨슈머가 던진 그대로 재시도 경로(D4)를 탄다. 1청크라도 성공(성공 수 > 0)이면 반환.
			throw firstBatchFailure;
		}
		return new SendResult(successCount, invalidTokens);
	}

	private MulticastMessage buildMessage(List<String> chunk, String title, String body) {
		return MulticastMessage.builder()
			.addAllTokens(chunk)
			.setNotification(Notification.builder().setTitle(title).setBody(body).build())
			.build();
	}

	private void collectInvalidTokens(BatchResponse response, List<String> chunk, List<String> invalidTokens) {
		List<SendResponse> responses = response.getResponses();
		for (int i = 0; i < responses.size(); i++) {
			SendResponse sendResponse = responses.get(i);
			if (sendResponse.isSuccessful()) {
				continue;
			}
			MessagingErrorCode code = sendResponse.getException().getMessagingErrorCode();
			if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
				invalidTokens.add(chunk.get(i));
			}
		}
	}
}
