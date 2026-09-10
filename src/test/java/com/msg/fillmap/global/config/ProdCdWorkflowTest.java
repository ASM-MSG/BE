package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * prod 컨테이너 배포(cd-prod.yml)의 계약 (MSG-595). dev 쪽 {@link DevApiCdWorkflowTest} 와 같은 방식이다.
 * 핵심은 "prod 는 이미지를 다시 만들지 않고 dev 가 검증한 sha 이미지에 prod 태그만 붙인다"는 것 —
 * 이 순서가 무너지면 dev 에서 본 바이너리와 prod 에 올라가는 바이너리가 다른 물건이 된다.
 */
@DisplayName("prod CD 계약 (MSG-595)")
class ProdCdWorkflowTest {

	private static final Path WORKFLOW = Path.of(".github/workflows/cd-prod.yml");

	@Test
	@DisplayName("승인 뒤 dev 가 검증한 sha 이미지를 찾아 prod 태그를 붙이고, 컨테이너를 올린 다음 8081 소유 PID까지 확인한다")
	void dev_검증_이미지에_prod_태그를_붙여_배포한다() throws IOException {
		String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);

		assertThat(workflow).contains("environment: production");

		// 순서: 이미지 찾기(HEAD → HEAD^2) → 태그 승격 → pull → up. 빌드는 이미지가 없을 때의 fallback 뿐이다
		int find = workflow.indexOf("- name: Find dev-verified image");
		int fallbackBuild = workflow.indexOf("- name: Build & push image (fallback)");
		int promote = workflow.indexOf("- name: Promote to prod tag");
		int pull = workflow.indexOf("docker compose -f docker-compose.app.yml pull -q api");
		int up = workflow.indexOf("docker compose -f docker-compose.app.yml up -d --wait --wait-timeout 180 api");
		assertThat(find).isGreaterThanOrEqualTo(0);
		assertThat(fallbackBuild).isGreaterThan(find);
		assertThat(promote).isGreaterThan(fallbackBuild);
		assertThat(pull).isGreaterThan(promote);
		assertThat(up).isGreaterThan(pull);

		assertThat(workflow).contains(
			"rev-parse -q --verify HEAD^2",
			"aws ecr describe-images --repository-name \"$REPO\" --image-ids imageTag=\"$t\"",
			"if: steps.find.outputs.tag == ''",
			"aws ecr put-image --repository-name \"$REPO\" --image-tag prod",
			// 서버 쪽은 dev 와 같은 신원 검사, 경로·포트만 prod
			"APP_ENV_FILE: /home/${{ secrets.PROD_EC2_USER }}/fillmap-prod/fillmap-prod.env",
			"docker inspect -f '{{.State.Pid}}' fillmap-api",
			"sudo ss -ltnpH 'sport = :8081'",
			"[ \"$listen\" = \"$cpid\" ]");

		// 러너 IP 는 배포 동안만 22 번에 열고 결과와 무관하게 닫는다 (prod SG 는 22 번 전체 공개가 아니다)
		int openSsh = workflow.indexOf("- name: Open SSH for this runner");
		int closeSsh = workflow.indexOf("- name: Close SSH for this runner");
		assertThat(openSsh).isGreaterThan(promote);
		assertThat(openSsh).isLessThan(pull);
		assertThat(closeSsh).isGreaterThan(up);
		assertThat(workflow.substring(closeSsh)).contains("if: always()", "revoke-security-group-ingress");
		// 이전 실행 잔재로 같은 규칙이 있어도 열기 스텝이 죽지 않는다 (죽으면 닫기까지 건너뛴다)
		assertThat(workflow.substring(openSsh, closeSsh)).contains("InvalidPermission.Duplicate");

		// jar 시절 형태로 되돌아가지 않는다
		assertThat(workflow).doesNotContain("Upload jar", "systemctl restart fillmap-prod", "app.jar");
	}
}
