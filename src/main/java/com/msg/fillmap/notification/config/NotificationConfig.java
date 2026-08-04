package com.msg.fillmap.notification.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.msg.fillmap.notification.repository.NotificationRepository;

/**
 * 알림 파이프라인 게이트 설정 (MSG-179 D11, AiConfig 선례). fillmap.notification.enabled 일 때만
 * @EnableScheduling 과 Kafka·FCM 빈이 뜬다 — 비활성 환경(로컬/CI)에서는 릴레이·컨슈머·스테일 배치가
 * 아예 없어 테스트 오염이 없다. 상시 빈(record·preference·엔티티/리포지토리)은 이 파일에 두지 않는다.
 *
 * Spring Boot 4 는 kafka 스타터 없이 spring-kafka 만 얹은 형상이라 자동설정에 기대지 않고
 * @EnableKafka + 팩토리 빈을 직접 정의한다 (AiConfig 의 RestClient.Builder 직접 제공과 같은 사정).
 */
@Slf4j
@Configuration
@EnableKafka
@EnableScheduling
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
public class NotificationConfig {

	// D4: FCM 순단을 덮는 컨슈머 백오프 — 초기 1s · 배수 2 · 재시도 8회(백오프 합 1+2+…+128 = 255s ≈ 4분).
	// env 별 튜닝 수요가 없어 프로퍼티가 아니라 상수로 둔다 (AiConfig 타임아웃 상수 선례).
	private static final long RETRY_INITIAL_INTERVAL_MS = 1000L;
	private static final double RETRY_MULTIPLIER = 2.0;
	private static final int RETRY_MAX_RETRIES = 8;
	private static final long RETRY_MAX_INTERVAL_MS = 128_000L;

	@Bean
	KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
		return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
	}

	/** 토픽은 앱이 생성한다 (D2) — 파티션 1: 단일 컨슈머·저처리량, 순서 보장 부수 획득. 보존은 브로커 기본. */
	@Bean
	NewTopic notificationTopic(NotificationProperties properties) {
		return TopicBuilder.name(properties.topic()).partitions(1).replicas(1).build();
	}

	@Bean
	ProducerFactory<String, String> notificationProducerFactory(
		@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
		// 메시지 = outbox id 문자열 (D13 thin payload) — StringSerializer, JSON 직렬화 설정 불요.
		return new DefaultKafkaProducerFactory<>(Map.of(
			ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
			ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
			ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
	}

	@Bean
	KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> notificationProducerFactory) {
		return new KafkaTemplate<>(notificationProducerFactory);
	}

	@Bean
	ConsumerFactory<String, String> notificationConsumerFactory(
		@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
		// earliest — 신규 그룹 최초 기동 시 기존 메시지 유실 방지 (스펙 §설정).
		// max.poll.* — DefaultErrorHandler 의 기본 BackOffHandler(DefaultBackOffHandler)는 리스너 스레드
		// sleep(시도당 1회, 단일 최대 128s — spring-kafka 4.1 실측)이라 poll 간 공백 = 백오프 + 레코드
		// 처리 시간. 기본 max.poll.records(500)에 FCM 지연이 겹치면 기본 max.poll.interval(300s)을 넘겨
		// 그룹 축출·리밸런스·중복 처리가 가능하다 — 워스트 케이스 위로 상향(600s), poll 당 소량(10) 제한.
		return new DefaultKafkaConsumerFactory<>(Map.of(
			ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
			ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
			ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
			ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
			ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000,
			ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10));
	}

	@Bean
	ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
		ConsumerFactory<String, String> notificationConsumerFactory, DefaultErrorHandler notificationErrorHandler) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(notificationConsumerFactory);
		factory.setCommonErrorHandler(notificationErrorHandler);
		return factory;
	}

	/**
	 * 재시도 상한 소진 시 DEAD 격리 (FR-4). 별도 DLT 토픽 없음 — outbox 행 자체가 죽은 편지함(D4).
	 * markDead 의 left(255) 가 긴 예외 메시지를 잘라 전이 실패를 막는다.
	 */
	@Bean
	ConsumerRecordRecoverer notificationDeadRecoverer(NotificationRepository notificationRepository,
		PlatformTransactionManager txManager) {
		TransactionTemplate tx = new TransactionTemplate(txManager);
		return (record, ex) -> {
			String value = String.valueOf(record.value());
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			try {
				long id = Long.parseLong(value);
				tx.executeWithoutResult(status -> notificationRepository.markDead(id, cause.toString()));
				log.warn("알림 재시도 상한 소진 — DEAD 격리: notificationId={}", id, cause);
			} catch (NumberFormatException e) {
				// outbox id 가 아닌 페이로드 — DEAD 기록할 행이 없다. 로그만 남기고 버린다 (poison 격리).
				log.error("DEAD 기록 불가 — outbox id 가 아닌 페이로드: value={}", value, cause);
			}
		};
	}

	@Bean
	DefaultErrorHandler notificationErrorHandler(ConsumerRecordRecoverer notificationDeadRecoverer) {
		ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(RETRY_MAX_RETRIES);
		backOff.setInitialInterval(RETRY_INITIAL_INTERVAL_MS);
		backOff.setMultiplier(RETRY_MULTIPLIER);
		backOff.setMaxInterval(RETRY_MAX_INTERVAL_MS);
		DefaultErrorHandler errorHandler = new DefaultErrorHandler(notificationDeadRecoverer, backOff);
		// 말폼드 레코드(비숫자 id)는 재시도해도 같은 결과 — 백오프 없이 즉시 recoverer 로 보내 단일
		// 파티션이 255s 를 낭비하지 않게 한다. recoverer 는 파싱 불가 시 error 후 폐기한다 (재던짐 없음).
		errorHandler.addNotRetryableExceptions(NumberFormatException.class);
		return errorHandler;
	}

	/**
	 * FCM 키 주입 (D9). enabled=true 인데 경로 미설정/파일 없음이면 여기서 기동 실패한다(fail-fast,
	 * MSG-260 철학). ProdRequiredEnvValidator 에 넣지 않는 이유 — 그 목록은 무조건 검사인데 이 키는
	 * enabled 조건부라서, 조건부 검증은 이 초기화 실패가 담당한다.
	 */
	@Bean
	FirebaseMessaging firebaseMessaging(NotificationProperties properties) throws IOException {
		String path = properties.fcm().credentialsPath();
		if (path == null || path.isBlank()) {
			throw new IllegalStateException(
				"fillmap.notification.fcm.credentials-path 미설정 — enabled=true 에는 FCM_CREDENTIALS_PATH 가 필수다");
		}
		try (FileInputStream credentials = new FileInputStream(path)) {
			FirebaseApp app = FirebaseApp.initializeApp(
				FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(credentials)).build());
			return FirebaseMessaging.getInstance(app);
		}
	}
}
