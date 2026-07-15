package com.msg.fillmap.video.config;

import java.util.concurrent.Executor;
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
	 */
	@Bean(ENCODING_EXECUTOR)
	public Executor encodingExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("encoding-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
