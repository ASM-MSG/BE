package com.msg.fillmap.video.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

	public static final String ENCODING_EXECUTOR = "encodingExecutor";

	/**
	 * 인코딩 전용 풀. ffmpeg 는 JVM 힙(-Xmx512m) 밖에서 도는 별도 프로세스라 힙 설정이 사용량을 막아주지
	 * 않는다. dev 는 t3.small(2GB, 여유 100MB 수준)이므로 동시 인코딩은 곧 OOM 이고, OOM Killer 는
	 * ffmpeg 가 아니라 메모리를 제일 많이 쓰는 JVM 을 고를 수 있다. 그래서 동시 실행을 1로 묶는다.
	 * 큐가 차면 CallerRuns 로 밀어내지 않고 예외를 던져 FAILED 로 기록되게 둔다.
	 *
	 * ponytail: 앱 내부 @Async + pool 1. 처리량이 부족하면 큐(SQS/Redis) + 별도 워커로 분리한다.
	 *
	 * 반환 타입을 구체 ThreadPoolTaskExecutor 로 노출한다 — AiBlurPoller 가 썸네일 재추출을 이 풀에 태워
	 * 인코딩과 직렬화하는데(MSG-149 P2-a), @Scheduled 가 만드는 taskScheduler 도 Executor 라 by-type 주입이
	 * 모호해지기 때문이다. highlightExecutor(MSG-456)가 생겨 구체 타입도 둘이 됐으므로, 주입은 필드명=빈
	 * 이름 매칭으로 갈린다. @Async(name)·bean 이름은 그대로다.
	 */
	@Bean(ENCODING_EXECUTOR)
	public ThreadPoolTaskExecutor encodingExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("encoding-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}

	/**
	 * 블러 꺼짐 경로의 후행 하이라이트 계산 전용 풀 (MSG-456 D-1). ffmpeg 를 돌리지 않고 AI HTTP 응답 대기만
	 * 하므로 인코딩 풀의 단일 프로세스 OOM 직렬화 전제와 무관하고, 인코딩 경로와 격리해 AI hang(최대 120초)이
	 * 다음 업로드의 인코딩 시작을 밀지 못하게 한다(헤드오브라인 블로킹 차단). 큐 50은 상류 인코딩 큐(50)가
	 * 쏟아낼 수 있는 최대 백로그와 맞춘 값 — 포화 거부(AbortPolicy)는 제출부가 catch 해 그 영상만 하이라이트
	 * null 로 남긴다(FR-8 의 실패 null 과 같은 결).
	 */
	@Bean
	public ThreadPoolTaskExecutor highlightExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("highlight-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
