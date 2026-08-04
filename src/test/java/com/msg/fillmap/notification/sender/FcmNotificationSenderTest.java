package com.msg.fillmap.notification.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.msg.fillmap.notification.sender.NotificationSender.SendResult;

/**
 * 500 청크 분할 검증 (MSG-179 — Codex 리뷰 반영). sendEachForMulticast 는 토큰 500개 하드 리밋 —
 * 초과분을 한 번에 보내면 요청 자체가 거부돼 반복 실패 → 영구 DEAD 로 빠진다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FcmNotificationSender — 500 청크 분할·결과 합산")
class FcmNotificationSenderTest {

	@Mock
	private FirebaseMessaging firebaseMessaging;

	@InjectMocks
	private FcmNotificationSender sender;

	@Test
	@DisplayName("501개 토큰은 500 단위 2회 호출로 나뉘고 successCount·invalidTokens 가 합산된다")
	void 오백_초과_토큰은_500_단위로_나눠_호출하고_결과를_합산한다() throws FirebaseMessagingException {
		List<String> tokens = IntStream.range(0, 501).mapToObj(i -> "t" + i).toList();

		SendResponse ok = mock(SendResponse.class);
		given(ok.isSuccessful()).willReturn(true);
		BatchResponse firstChunk = mock(BatchResponse.class);
		given(firstChunk.getResponses()).willReturn(Collections.nCopies(500, ok));
		given(firstChunk.getSuccessCount()).willReturn(500);

		FirebaseMessagingException unregistered = mock(FirebaseMessagingException.class);
		given(unregistered.getMessagingErrorCode()).willReturn(MessagingErrorCode.UNREGISTERED);
		SendResponse failed = mock(SendResponse.class);
		given(failed.isSuccessful()).willReturn(false);
		given(failed.getException()).willReturn(unregistered);
		BatchResponse secondChunk = mock(BatchResponse.class);
		given(secondChunk.getResponses()).willReturn(List.of(failed));
		given(secondChunk.getSuccessCount()).willReturn(0);

		given(firebaseMessaging.sendEachForMulticast(any()))
			.willReturn(firstChunk)
			.willReturn(secondChunk);

		SendResult result = sender.send(tokens, "제목", "본문");

		then(firebaseMessaging).should(times(2)).sendEachForMulticast(any());
		assertThat(result.successCount()).isEqualTo(500);
		// 두 번째 청크의 응답 인덱스 0 = 전역 501번째 토큰 — 청크 오프셋 매핑이 맞아야 t500 이 나온다.
		assertThat(result.invalidTokens()).containsExactly("t500");
	}
}
