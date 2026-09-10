package com.msg.fillmap.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.OrgAccountRequest;
import com.msg.fillmap.user.entity.OrgAccountRequestStatus;

/**
 * 계정 발급 요청 접수 API (MSG-499 FR-6, 실 DB). 검증 대상이 인가 경계(비로그인 허용)와 UPSERT 저장
 * 결과라 목으로는 잡히지 않는다. {@code @Transactional} 롤백 격리로 공유 로컬 DB 에 요청 행을 남기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("계정 발급 요청 접수 API (MSG-499, 실 DB)")
class OrgAccountRequestControllerTest {

	private static final String URL = "/api/org-account-requests";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private EntityManager entityManager;

	private String uniqueEmail() {
		return "apply-" + UUID.randomUUID() + "@fillmap.dev";
	}

	private String body(String email, String orgName) {
		return """
			{"orgName": "%s", "contactName": "김담당", "contactPhone": "010-1234-5678",
			 "email": "%s", "eventName": "서면 겨울 축제", "content": "계정을 신청합니다"}"""
			.formatted(orgName, email);
	}

	private List<OrgAccountRequest> 이메일로_찾는다(String email) {
		entityManager.clear();
		return entityManager
			.createQuery("SELECT r FROM OrgAccountRequest r WHERE r.email = :email", OrgAccountRequest.class)
			.setParameter("email", email)
			.getResultList();
	}

	@Nested
	@DisplayName("접수")
	class Create {

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("토큰 없이 접수하면 대기 상태로 저장된다")
		void 토큰_없이_접수하면_대기_상태로_저장된다() throws Exception {
			String email = uniqueEmail();

			mockMvc.perform(post(URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body(email, "부산진구청")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			OrgAccountRequest saved = 이메일로_찾는다(email).getFirst();
			assertThat(saved.getStatus()).isEqualTo(OrgAccountRequestStatus.PENDING);
			assertThat(saved.getOrgName()).isEqualTo("부산진구청");
			assertThat(saved.getContactName()).isEqualTo("김담당");
			assertThat(saved.getEventName()).isEqualTo("서면 겨울 축제");
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("같은 이메일의 재접수는 행이 늘지 않고 내용과 마지막 접수 시각이 갱신된다")
		void 같은_이메일의_재접수는_행이_늘지_않고_내용이_갱신된다() throws Exception {
			String email = uniqueEmail();
			mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(email, "부산진구청")))
				.andExpect(status().isOk());
			OrgAccountRequest first = 이메일로_찾는다(email).getFirst();

			mockMvc.perform(post(URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body(email, "부산진구청 문화체육과")))
				.andExpect(status().isOk());

			List<OrgAccountRequest> rows = 이메일로_찾는다(email);
			assertThat(rows).hasSize(1);
			assertThat(rows.getFirst().getOrgName()).isEqualTo("부산진구청 문화체육과");
			// 최초 접수 시각은 보존되고 마지막 접수 시각만 뒤로 간다 — 심사의 검토 기준 시각이 갱신되는 것.
			assertThat(rows.getFirst().getCreatedAt()).isEqualTo(first.getCreatedAt());
			assertThat(rows.getFirst().getUpdatedAt()).isAfterOrEqualTo(first.getUpdatedAt());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("무효 토큰을 동봉하면 401 이다 — permitAll 이지 필터 skip 이 아니다")
		void 무효_토큰을_동봉하면_401이다() throws Exception {
			mockMvc.perform(post(URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body(uniqueEmail(), "부산진구청")))
				.andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@DisplayName("입력 검증")
	class Validation {

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("필수 필드가 비면 400 이고 행이 생기지 않는다")
		void 필수_필드가_비면_400이다() throws Exception {
			String email = uniqueEmail();
			String missingOrgName = """
				{"contactName": "김담당", "contactPhone": "010-1234-5678",
				 "email": "%s", "eventName": "서면 겨울 축제", "content": "계정을 신청합니다"}"""
				.formatted(email);

			mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(missingOrgName))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));

			assertThat(이메일로_찾는다(email)).isEmpty();
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("이메일 형식이 아니면 400 이다")
		void 이메일_형식이_아니면_400이다() throws Exception {
			mockMvc.perform(post(URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body("not-an-email", "부산진구청")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("연락처 형식 위반과 담당자 이름 길이 위반은 400 이다")
		void 연락처_형식과_담당자_이름_길이_위반은_400이다() throws Exception {
			String badPhone = """
				{"orgName": "부산진구청", "contactName": "김담당", "contactPhone": "----------",
				 "email": "%s", "eventName": "축제", "content": "신청"}""".formatted(uniqueEmail());
			String shortName = """
				{"orgName": "부산진구청", "contactName": "김", "contactPhone": "010-1234-5678",
				 "email": "%s", "eventName": "축제", "content": "신청"}""".formatted(uniqueEmail());

			for (String content : new String[] {badPhone, shortName}) {
				mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(content))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(400));
			}
		}
	}
}
