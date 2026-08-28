package com.msg.fillmap.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.OrgEmailChangeRequest;
import com.msg.fillmap.user.entity.OrgEmailChangeStatus;
import com.msg.fillmap.user.entity.User;

/**
 * 행사 운영자 계정 보안 스키마 영속 (MSG-497 V46, 실 DB). 새 컬럼 2개와 아이디 변경 요청 테이블이
 * 실제로 매핑·저장되는지를 본다 — 엔티티 필드만 늘려서는 DDL 누락이나 NOT NULL·CHECK 위반을 못 잡는다.
 * {@code @Transactional} 롤백 격리로 공유 로컬 DB 에 계정과 요청 행을 남기지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 운영자 계정 보안 스키마 (V46, 실 DB)")
class OrgAccountSecurityPersistenceTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrgEmailChangeRequestRepository requestRepository;

	private User saveUser() {
		return userRepository.saveAndFlush(
			User.createLocalUser("org-" + UUID.randomUUID() + "@fillmap.dev", "{noop}pw", "담당자"));
	}

	private LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	@Nested
	@DisplayName("users 신규 컬럼")
	class UserColumns {

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("기존 행은 강제 변경 플래그가 false 로 시작한다 — DEFAULT FALSE")
		void 새로_저장한_사용자는_강제_변경_플래그가_거짓이다() {
			User saved = saveUser();

			assertThat(saved.isPasswordMustChange()).isFalse();
			assertThat(saved.getContactPhone()).isNull();
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("강제 변경 플래그가 참인 사용자를 파생 쿼리가 찾아낸다 — 게이트 판정축")
		void 강제_변경_플래그로_사용자를_판정한다() {
			User target = saveUser();
			ReflectionTestUtils.setField(target, "passwordMustChange", true);
			User other = saveUser();
			userRepository.flush();

			assertThat(userRepository.existsByIdAndPasswordMustChangeTrue(target.getId())).isTrue();
			assertThat(userRepository.existsByIdAndPasswordMustChangeTrue(other.getId())).isFalse();
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("비밀번호 교체는 해시와 강제 변경 플래그를 함께 반영한다")
		void 비밀번호를_교체하면_강제_변경_플래그가_내려간다() {
			User user = saveUser();
			ReflectionTestUtils.setField(user, "passwordMustChange", true);
			userRepository.flush();

			user.changePassword("{bcrypt}new-hash");
			userRepository.flush();

			User reloaded = userRepository.findById(user.getId()).orElseThrow();
			assertThat(reloaded.getPasswordHash()).isEqualTo("{bcrypt}new-hash");
			assertThat(reloaded.isPasswordMustChange()).isFalse();
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("담당자 이름·연락처 변경이 저장된다")
		void 담당자_이름과_연락처를_바꾸면_저장된다() {
			User user = saveUser();

			user.updateOrgContact("김담당", "010-1234-5678");
			userRepository.flush();

			User reloaded = userRepository.findById(user.getId()).orElseThrow();
			assertThat(reloaded.getNickname()).isEqualTo("김담당");
			assertThat(reloaded.getContactPhone()).isEqualTo("010-1234-5678");
		}
	}

	@Nested
	@DisplayName("아이디 변경 요청 테이블")
	class EmailChangeRequests {

		// 검증: FR-USER-16
		@Test
		@DisplayName("접수하면 대기 상태 행이 하나 생긴다")
		void 아이디_변경_요청이_대기_상태로_저장된다() {
			User user = saveUser();
			LocalDateTime now = now();

			requestRepository.upsertPending(user.getId(), "new@fillmap.dev", now);

			OrgEmailChangeRequest saved = requestRepository.findAllByUserId(user.getId()).getFirst();
			assertThat(saved.getRequestedEmail()).isEqualTo("new@fillmap.dev");
			assertThat(saved.getStatus()).isEqualTo(OrgEmailChangeStatus.PENDING);
			assertThat(saved.getCreatedAt()).isEqualTo(now);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("대기 요청이 있으면 재요청이 그 행을 갱신한다 — 행 수 1 유지")
		void 대기_요청이_있으면_재요청이_그_행을_갱신한다() {
			User user = saveUser();
			requestRepository.upsertPending(user.getId(), "first@fillmap.dev", now());

			requestRepository.upsertPending(user.getId(), "second@fillmap.dev", now());

			assertThat(requestRepository.findAllByUserId(user.getId()))
				.hasSize(1)
				.first()
				.extracting(OrgEmailChangeRequest::getRequestedEmail)
				.isEqualTo("second@fillmap.dev");
		}
	}
}
