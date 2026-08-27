package com.msg.fillmap.video.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.zaxxer.hikari.HikariDataSource;

import com.msg.fillmap.event.service.EventNotificationScheduler;
import com.msg.fillmap.notification.config.NotificationConfig;
import com.msg.fillmap.notification.consumer.NotificationConsumer;
import com.msg.fillmap.notification.relay.HotZoneEntryDetector;
import com.msg.fillmap.notification.relay.NotificationBacklogMetrics;
import com.msg.fillmap.notification.relay.NotificationRelay;
import com.msg.fillmap.notification.relay.StaleTokenCleaner;
import com.msg.fillmap.notification.sender.FcmNotificationSender;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.streak.service.StreakRemindScheduler;
import com.msg.fillmap.usergrid.service.WeeklySummaryScheduler;
import com.msg.fillmap.video.service.AiBlurPoller;
import com.msg.fillmap.video.service.EncodingJobPoller;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:postgresql://localhost:5432/fillmap",
	"spring.datasource.username=user",
	"spring.datasource.password=user1234",
	"spring.jpa.hibernate.ddl-auto=none",
	"ai.enabled=true",
	"ai.blur-enabled=true",
	"fillmap.video.encoding-job.poll-interval=PT1H"
})
@ActiveProfiles({"dev", "encoding-worker"})
@DisplayName("encoding-worker 프로필 컨텍스트")
class EncodingWorkerContextTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Environment environment;

	@Autowired
	private DataSource dataSource;

	@Test
	void 워커는_인코딩과_outbox기록만_유지하고_중복_스케줄러를_띄우지_않는다() {
		assertThat(context.getBean(EncodingJobPoller.class)).isNotNull();
		assertThat(context.getBean(NotificationCommandService.class)).isNotNull();
		assertThat(context.getBeansOfType(AiBlurPoller.class)).isEmpty();
		assertThat(context.getBeansOfType(EventNotificationScheduler.class)).isEmpty();
	}

	@Test
	void 워커는_알림_조건부빈을_띄우지_않는다() {
		assertThat(context.getBeansOfType(NotificationConfig.class)).isEmpty();
		assertThat(context.getBeansOfType(NotificationConsumer.class)).isEmpty();
		assertThat(context.getBeansOfType(NotificationRelay.class)).isEmpty();
		assertThat(context.getBeansOfType(StreakRemindScheduler.class)).isEmpty();
		assertThat(context.getBeansOfType(WeeklySummaryScheduler.class)).isEmpty();
		assertThat(context.getBeansOfType(HotZoneEntryDetector.class)).isEmpty();
		assertThat(context.getBeansOfType(StaleTokenCleaner.class)).isEmpty();
		assertThat(context.getBeansOfType(NotificationBacklogMetrics.class)).isEmpty();
		assertThat(context.getBeansOfType(FcmNotificationSender.class)).isEmpty();
	}

	@Test
	void 워커는_8081과_작은_DB풀을_쓰고_Flyway와_Redis헬스를_끈다() {
		assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8081);
		assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
		assertThat(environment.getProperty("management.health.redis.enabled", Boolean.class)).isFalse();
		assertThat(environment.getProperty("fillmap.notification.enabled", Boolean.class)).isFalse();
		assertThat(environment.getProperty("fillmap.zone.seed.enabled", Boolean.class)).isFalse();
		assertThat(environment.getProperty("fillmap.event.seed.enabled", Boolean.class)).isFalse();
		assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
		assertThat(dataSource).isInstanceOfSatisfying(HikariDataSource.class, hikari -> {
			assertThat(hikari.getMaximumPoolSize()).isEqualTo(2);
			assertThat(hikari.getMinimumIdle()).isEqualTo(1);
		});
	}
}
