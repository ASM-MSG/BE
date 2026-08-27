package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인코딩 워커 dev CD 계약 (MSG-494)")
class EncodingWorkerCdWorkflowTest {

	private static final Path WORKFLOW = Path.of(".github/workflows/cd-dev.yml");

	@Test
	@DisplayName("BE health 성공 뒤 같은 JAR를 워커에 배포하고 8081 소유 PID까지 확인한다")
	void BE_health_성공_뒤_워커를_배포하고_검증한다() throws IOException {
		String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);

		int backendHealth = workflow.indexOf("- name: Health check dev service");
		int workerUpload = workflow.indexOf("- name: Upload jar to encoding worker EC2");
		assertThat(backendHealth).isGreaterThanOrEqualTo(0);
		assertThat(workerUpload).isGreaterThan(backendHealth);
		assertThat(workflow).contains(
			"host: ${{ secrets.AI_EC2_HOST }}",
			"username: ${{ secrets.AI_EC2_USER }}",
			"key: ${{ secrets.AI_EC2_SSH_KEY }}",
			"source: build/libs/fillmap-0.0.1-SNAPSHOT.jar",
			"mv -f fillmap-0.0.1-SNAPSHOT.jar app.jar",
			"sudo systemctl restart fillmap-encoding-worker",
			"http://127.0.0.1:8081/actuator/health",
			"systemctl show -p MainPID --value fillmap-encoding-worker",
			"sudo ss -ltnpH 'sport = :8081'",
			"[ \"$listen_pids\" = \"$main_pid\" ]");
	}
}
