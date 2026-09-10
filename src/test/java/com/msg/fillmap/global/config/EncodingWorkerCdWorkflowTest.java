package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인코딩 워커 dev CD 계약 (MSG-494 · MSG-589 컨테이너 전환 반영)")
class EncodingWorkerCdWorkflowTest {

	private static final Path WORKFLOW = Path.of(".github/workflows/cd-dev.yml");

	@Test
	@DisplayName("BE 배포가 끝난 뒤 같은 이미지를 워커에 배포하고 8081 소유 PID까지 확인한다")
	void BE_배포_뒤_워커를_배포하고_검증한다() throws IOException {
		String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);

		// 워커는 Flyway 를 끄고 validate 만 하므로 api(deploy-dev)가 마이그레이션을 끝낸 뒤 떠야 한다 (MSG-494 §9.2).
		// 잡 순서는 파일 위치가 아니라 needs 가 보장한다.
		int apiDeploy = workflow.indexOf("- name: Deploy api container");
		// 파일 머리 주석에도 "deploy-worker:" 가 있어 줄 시작 + 개행으로 잡 정의만 잡는다
		int workerJob = workflow.indexOf("\n  deploy-worker:\n");
		assertThat(apiDeploy).isGreaterThanOrEqualTo(0);
		assertThat(workerJob).isGreaterThan(apiDeploy);
		assertThat(workflow.substring(workerJob)).contains("needs: [ build-image, deploy-dev ]");

		assertThat(workflow).contains(
			"host: ${{ secrets.AI_EC2_HOST }}",
			"username: ${{ secrets.AI_EC2_USER }}",
			"key: ${{ secrets.AI_EC2_SSH_KEY }}",
			// 같은 이미지 — build-image 잡의 태그를 그대로 받는다
			"TAG: ${{ needs.build-image.outputs.tag }}",
			"source: docker-compose.app.yml",
			"sudo TAG=\"$TAG\" docker compose -f docker-compose.app.yml pull -q worker",
			"sudo systemctl disable --now fillmap-encoding-worker",
			"sudo systemctl mask fillmap-encoding-worker",
			"sudo TAG=\"$TAG\" docker compose -f docker-compose.app.yml up -d --wait --wait-timeout 180 worker",
			// 8081 리스너가 방금 띄운 컨테이너의 프로세스인지 (jar 시절 MainPID 검사의 컨테이너판)
			"sudo docker inspect -f '{{.State.Pid}}' fillmap-encoding-worker",
			"sudo ss -ltnpH 'sport = :8081'",
			"[ \"$listen\" = \"$cpid\" ]");
	}
}
