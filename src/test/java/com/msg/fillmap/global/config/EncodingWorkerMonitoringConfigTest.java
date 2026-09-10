package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인코딩 워커 상시 관측 설정 (MSG-494)")
class EncodingWorkerMonitoringConfigTest {

	@Test
	@DisplayName("AI EC2의 Prometheus가 워커를 수집하고 Grafana에서 선택할 수 있다")
	void prometheus가_워커를_수집하고_Grafana에서_선택할_수_있다() throws IOException {
		String compose = read("monitoring/prod/docker-compose.yml");
		String prometheus = read("monitoring/prod/prometheus/prometheus.yml");
		String dashboard = read("monitoring/prod/grafana/dashboards/fillmap-prod-overview.json");

		assertThat(compose).contains("host.docker.internal:host-gateway");
		assertThat(prometheus).contains(
			"job_name: fillmap-dev",
			"job_name: fillmap-encoding-worker",
			"targets: [\"host.docker.internal:8081\"]");
		assertThat(dashboard).contains(
			"fillmap-prod,fillmap-dev,fillmap-encoding-worker",
			"\"value\": \"fillmap-encoding-worker\"");
	}

	private String read(String path) throws IOException {
		return Files.readString(Path.of(path), StandardCharsets.UTF_8);
	}
}
