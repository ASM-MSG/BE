package com.msg.fillmap.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.google.firebase.messaging.FirebaseMessaging;
import com.msg.fillmap.notification.consumer.NotificationConsumer;
import com.msg.fillmap.notification.relay.HotZoneEntryDetector;
import com.msg.fillmap.notification.relay.NotificationRelay;
import com.msg.fillmap.notification.relay.StaleTokenCleaner;
import com.msg.fillmap.notification.sender.FcmNotificationSender;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.notification.service.NotificationPreferenceService;
import com.msg.fillmap.streak.service.StreakRemindScheduler;

/**
 * notification 게이트 검증 (MSG-179 D11, AiEnabledContextTest 선례의 반대편). 기본 off 컨텍스트에서
 * 파이프라인 빈이 아예 없음을 증명한다 — 로컬·CI 가 Kafka·FCM 없이 무오염으로 green 인 근거.
 * 접점 빈(record — MSG-181, preference — MSG-180)은 게이트 밖 상시임을 함께 고정한다.
 */
@SpringBootTest
@DisplayName("notification 게이트 — 기본 off 컨텍스트")
class NotificationDisabledContextTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void notification_비활성이면_릴레이와_컨슈머_빈이_없다() {
		assertThat(context.getBeansOfType(NotificationRelay.class)).isEmpty();
		assertThat(context.getBeansOfType(NotificationConsumer.class)).isEmpty();
		assertThat(context.getBeansOfType(StaleTokenCleaner.class)).isEmpty();
		assertThat(context.getBeansOfType(FcmNotificationSender.class)).isEmpty();
		assertThat(context.getBeansOfType(FirebaseMessaging.class)).isEmpty();
	}

	@Test
	void notification_비활성이면_검출기와_리마인드_스케줄러_빈이_없다() {
		assertThat(context.getBeansOfType(HotZoneEntryDetector.class)).isEmpty();
		assertThat(context.getBeansOfType(StreakRemindScheduler.class)).isEmpty();
	}

	@Test
	void record와_preference_빈은_비활성에서도_존재한다() {
		assertThat(context.getBean(NotificationCommandService.class)).isNotNull();
		assertThat(context.getBean(NotificationPreferenceService.class)).isNotNull();
	}
}
