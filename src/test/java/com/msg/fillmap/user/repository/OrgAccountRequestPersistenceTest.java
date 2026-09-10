package com.msg.fillmap.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.OrgAccountRequest;
import com.msg.fillmap.user.entity.OrgAccountRequestStatus;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 계정 발급 요청 스키마와 접수 UPSERT 영속 (MSG-499 V48, 실 DB). 부분 유니크 인덱스 위의 ON CONFLICT 라
 * 실제 DB 가 있어야 의미가 있다 — "대기 1행 수렴"과 "최초 접수 시각 보존"은 인덱스와 EXCLUDED 갱신
 * 목록이 함께 만드는 성질이라 엔티티만 봐서는 검증되지 않는다.
 * {@code @Transactional} 롤백 격리로 공유 로컬 DB 에 요청 행과 계정을 남기지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("계정 발급 요청 스키마 (V48, 실 DB)")
class OrgAccountRequestPersistenceTest {

	@Autowired
	private OrgAccountRequestRepository requestRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager entityManager;

	private String uniqueEmail() {
		return "org-" + UUID.randomUUID() + "@fillmap.dev";
	}

	private LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private int 접수한다(String email, String orgName, LocalDateTime at) {
		int affected = requestRepository.upsertPending(orgName, "김담당", "010-1234-5678", email,
			"서면 겨울 축제", "행사 등재를 위해 계정을 신청합니다", at);
		entityManager.clear();
		return affected;
	}

	private List<OrgAccountRequest> 이메일로_찾는다(String email) {
		return entityManager
			.createQuery("SELECT r FROM OrgAccountRequest r WHERE r.email = :email", OrgAccountRequest.class)
			.setParameter("email", email)
			.getResultList();
	}

	@Nested
	@DisplayName("접수 UPSERT")
	class Upsert {

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("접수하면 대기 상태 행이 하나 생긴다")
		void 접수하면_대기_상태_행이_하나_생긴다() {
			String email = uniqueEmail();
			LocalDateTime at = now();

			접수한다(email, "부산진구청", at);

			OrgAccountRequest saved = 이메일로_찾는다(email).getFirst();
			assertThat(saved.getOrgName()).isEqualTo("부산진구청");
			assertThat(saved.getStatus()).isEqualTo(OrgAccountRequestStatus.PENDING);
			assertThat(saved.getCreatedAt()).isEqualTo(at);
			assertThat(saved.getUpdatedAt()).isEqualTo(at);
			assertThat(saved.getProcessedAt()).isNull();
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("같은 이메일의 재접수는 행이 늘지 않고 내용과 마지막 접수 시각이 갱신된다")
		void 같은_이메일의_재접수는_행이_늘지_않고_내용과_마지막_접수_시각이_갱신된다() {
			String email = uniqueEmail();
			LocalDateTime first = now();
			접수한다(email, "부산진구청", first);
			LocalDateTime second = first.plusMinutes(5);

			접수한다(email, "부산진구청 문화체육과", second);

			List<OrgAccountRequest> rows = 이메일로_찾는다(email);
			assertThat(rows).hasSize(1);
			assertThat(rows.getFirst().getOrgName()).isEqualTo("부산진구청 문화체육과");
			assertThat(rows.getFirst().getUpdatedAt()).isEqualTo(second);
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("재접수해도 최초 접수 시각은 보존된다")
		void 재접수해도_최초_접수_시각은_보존된다() {
			String email = uniqueEmail();
			LocalDateTime first = now();
			접수한다(email, "부산진구청", first);

			접수한다(email, "부산진구청", first.plusMinutes(5));

			assertThat(이메일로_찾는다(email).getFirst().getCreatedAt()).isEqualTo(first);
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("반려된 이메일은 새 대기 행으로 다시 접수된다 — 부분 유니크 인덱스 밖")
		void 반려된_이메일은_새_대기_행으로_다시_접수된다() {
			String email = uniqueEmail();
			접수한다(email, "부산진구청", now());
			OrgAccountRequest rejected = 이메일로_찾는다(email).getFirst();
			rejected.reject("제출 서류 누락", now());
			entityManager.flush();
			entityManager.clear();

			접수한다(email, "부산진구청", now());

			assertThat(이메일로_찾는다(email))
				.hasSize(2)
				.extracting(OrgAccountRequest::getStatus)
				.containsExactlyInAnyOrder(OrgAccountRequestStatus.REJECTED, OrgAccountRequestStatus.PENDING);
		}
	}

	@Nested
	@DisplayName("상태 전이와 큐 조회")
	class Transitions {

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("승인 전이는 발급 계정 id 와 처리 시각을 남긴다")
		void 승인_전이는_발급_계정_id와_처리_시각을_남긴다() {
			String email = uniqueEmail();
			접수한다(email, "부산진구청", now());
			User issued = userRepository.saveAndFlush(
				User.createOrgUser(email, "{noop}pw", "김담당", "010-1234-5678", "부산진구청"));
			LocalDateTime processedAt = now();

			OrgAccountRequest request = 이메일로_찾는다(email).getFirst();
			request.issue(issued.getId(), processedAt);
			entityManager.flush();
			entityManager.clear();

			OrgAccountRequest reloaded = 이메일로_찾는다(email).getFirst();
			assertThat(reloaded.getStatus()).isEqualTo(OrgAccountRequestStatus.ISSUED);
			assertThat(reloaded.getIssuedUserId()).isEqualTo(issued.getId());
			assertThat(reloaded.getProcessedAt()).isEqualTo(processedAt);
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("대기 큐는 마지막 접수 최신순으로 조회되고 건수가 상태별로 세어진다")
		void 대기_큐는_마지막_접수_최신순으로_조회되고_건수가_상태별로_세어진다() {
			LocalDateTime base = now();
			String older = uniqueEmail();
			String newer = uniqueEmail();
			접수한다(older, "먼저 접수", base);
			접수한다(newer, "나중 접수", base.plusMinutes(1));
			long pendingBefore = requestRepository.countByStatus(OrgAccountRequestStatus.PENDING);

			List<OrgAccountRequest> page = requestRepository
				.findAllByStatusOrderByUpdatedAtDesc(OrgAccountRequestStatus.PENDING, PageRequest.of(0, 100))
				.getContent();

			assertThat(page)
				.extracting(OrgAccountRequest::getEmail)
				.containsSubsequence(newer, older);
			assertThat(pendingBefore).isGreaterThanOrEqualTo(2);
		}
	}

	@Nested
	@DisplayName("발급 계정 팩토리와 목록")
	class OrgUsers {

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("발급 계정은 ORG·LOCAL 이고 강제 변경 플래그가 켜진 채 저장된다")
		void 발급_계정은_ORG_LOCAL이고_강제_변경_플래그가_켜진_채_저장된다() {
			String email = uniqueEmail();

			User saved = userRepository.saveAndFlush(
				User.createOrgUser(email, "{noop}pw", "김담당", "010-1234-5678", "부산진구청"));
			entityManager.clear();

			User reloaded = userRepository.findById(saved.getId()).orElseThrow();
			assertThat(reloaded.getRole()).isEqualTo(UserRole.ORG);
			assertThat(reloaded.getProvider()).isEqualTo(AuthProvider.LOCAL);
			assertThat(reloaded.isPasswordMustChange()).isTrue();
			assertThat(reloaded.getOrgName()).isEqualTo("부산진구청");
			assertThat(reloaded.getContactPhone()).isEqualTo("010-1234-5678");
			assertThat(reloaded.getNickname()).isEqualTo("김담당");
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("초기 비밀번호 재발급은 해시만 바꾸고 강제 변경 플래그를 유지한다")
		void 초기_비밀번호_재발급은_해시만_바꾸고_강제_변경_플래그를_유지한다() {
			User user = userRepository.saveAndFlush(
				User.createOrgUser(uniqueEmail(), "{noop}old", "김담당", "010-1234-5678", "부산진구청"));

			user.resetInitialPassword("{noop}new");
			entityManager.flush();
			entityManager.clear();

			User reloaded = userRepository.findById(user.getId()).orElseThrow();
			assertThat(reloaded.getPasswordHash()).isEqualTo("{noop}new");
			assertThat(reloaded.isPasswordMustChange()).isTrue();
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("계정 목록의 이메일 필터는 ORG·LOCAL 계정만 찾는다")
		void 계정_목록의_이메일_필터는_ORG_LOCAL_계정만_찾는다() {
			String orgEmail = uniqueEmail();
			String userEmail = uniqueEmail();
			userRepository.saveAndFlush(
				User.createOrgUser(orgEmail, "{noop}pw", "김담당", "010-1234-5678", "부산진구청"));
			userRepository.saveAndFlush(User.createLocalUser(userEmail, "{noop}pw", "일반사용자"));
			entityManager.clear();

			assertThat(userRepository.findAllByRoleAndProviderAndEmailOrderByCreatedAtDesc(
				UserRole.ORG, AuthProvider.LOCAL, orgEmail, PageRequest.of(0, 20)).getContent()).hasSize(1);
			assertThat(userRepository.findAllByRoleAndProviderAndEmailOrderByCreatedAtDesc(
				UserRole.ORG, AuthProvider.LOCAL, userEmail, PageRequest.of(0, 20)).getContent()).isEmpty();
		}
	}
}
