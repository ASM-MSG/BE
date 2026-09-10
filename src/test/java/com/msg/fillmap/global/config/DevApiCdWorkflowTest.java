package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * dev api 컨테이너 배포(deploy-dev 잡)의 계약 (MSG-589). 워커 쪽 {@link EncodingWorkerCdWorkflowTest} 와 같은 방식이다.
 * 2026-09-10 첫 컨테이너 배포가 구 systemd 앱이 8080 을 쥔 채 신원 검사에서 멈췄다 — mask 가 유닛 파일 존재로 거부됐는데
 * `|| true` 가 오류를 삼켰다. 그 수정 블록이 조용히 사라지거나 순서가 바뀌는 회귀를 여기서 막는다.
 */
@DisplayName("dev api CD 계약 (MSG-589)")
class DevApiCdWorkflowTest {

	private static final Path WORKFLOW = Path.of(".github/workflows/cd-dev.yml");

	@Test
	@DisplayName("이미지를 먼저 받고, 구 systemd 유닛을 치운 뒤 mask 하고, 컨테이너를 올린 다음 8080 소유 PID까지 확인한다")
	void 이미지_pull_뒤_구_유닛을_치우고_컨테이너를_올린다() throws IOException {
		String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
		int deployDevStart = workflow.indexOf("\n  deploy-dev:\n");
		int deployWorkerStart = workflow.indexOf("\n  deploy-worker:\n");
		assertThat(deployDevStart).isGreaterThanOrEqualTo(0);
		assertThat(deployWorkerStart).isGreaterThan(deployDevStart);
		String deployDev = workflow.substring(deployDevStart, deployWorkerStart);

		// 순서: pull → 구 유닛 정리 → up. pull 이 먼저라야 ECR 장애가 돌고 있는 서비스를 내리지 않는다 (Codex 리뷰 P1)
		int pull = deployDev.indexOf("docker compose -f docker-compose.app.yml pull -q api");
		int unitCleanup = deployDev.indexOf("if [ -f /etc/systemd/system/fillmap-dev.service ]; then");
		int up = deployDev.indexOf("docker compose -f docker-compose.app.yml up -d --wait --wait-timeout 180 api");
		assertThat(pull).isGreaterThanOrEqualTo(0);
		assertThat(unitCleanup).isGreaterThan(pull);
		assertThat(up).isGreaterThan(unitCleanup);

		// 구 유닛 정리: stop+disable → 유닛 파일을 홈으로 → daemon-reload → mask. 유닛 파일이 /etc/systemd/system 에
		// 직접 있으면 mask 가 "already exists" 로 거부되므로 파일을 먼저 치워야 한다
		assertThat(deployDev).contains(
			"sudo systemctl disable --now fillmap-dev",
			"sudo mv /etc/systemd/system/fillmap-dev.service ~/fillmap-dev.service.pre-msg589",
			"sudo systemctl daemon-reload",
			"sudo systemctl mask fillmap-dev\n");
		// 오류를 삼키던 형태로 되돌아가지 않는다
		assertThat(deployDev).doesNotContain("mask --now fillmap-dev", "mask fillmap-dev 2>/dev/null");

		// 신원 검사: healthy 만으론 부족 — 8080 리스너가 이 컨테이너의 프로세스여야 한다 (호스트 네트워크)
		assertThat(deployDev).contains(
			"docker inspect -f '{{.Config.Image}}' fillmap-api",
			"docker inspect -f '{{.State.Pid}}' fillmap-api",
			"sudo ss -ltnpH 'sport = :8080'",
			"[ \"$listen\" = \"$cpid\" ]");
	}
}
