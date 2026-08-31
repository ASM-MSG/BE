package com.msg.fillmap.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 행사 운영자 역할값 영속 (MSG-496 FR-1 재료, 실 DB). V1 의 chk_users_role 이 USER·ADMIN 만
 * 허용하고 있어 V45 가 제약을 재정의했는데, enum 상수만 늘려서는 DB CHECK 위반을 못 잡는다 —
 * saveAndFlush 로 INSERT 를 당겨 제약을 실제로 통과하는지 본다. 역할을 바꾸는 생성 경로는
 * 아직 없어(계정 발급 API 는 MSG-499) 필드를 직접 세팅한다.
 * @Transactional 롤백 격리로 공유 로컬 DB 에 계정을 남기지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 운영자 역할 영속 (V45, 실 DB)")
class UserRoleOrgPersistenceTest {

	@Autowired
	private UserRepository userRepository;

	private User localUser(String prefix) {
		return User.createLocalUser(prefix + "-" + UUID.randomUUID() + "@fillmap.dev", "{noop}pw", "역할테스트");
	}

	private User saveWithRole(String prefix, UserRole role) {
		User user = localUser(prefix);
		ReflectionTestUtils.setField(user, "role", role);
		return userRepository.saveAndFlush(user);
	}

	// 검증: FR-AUTH-14
	@Test
	@DisplayName("role = ORG 사용자가 저장된다 — V45 CHECK 재정의 검증")
	void ORG_역할_사용자를_저장하면_제약을_통과한다() {
		User saved = saveWithRole("org", UserRole.ORG);

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getRole()).isEqualTo(UserRole.ORG);
	}

	// 검증: FR-AUTH-14
	@Test
	@DisplayName("기존 USER·ADMIN 역할도 그대로 저장된다 — V45 는 값 추가일 뿐 기존 행에 무영향")
	void 기존_USER와_ADMIN_행은_마이그레이션_후에도_유효하다() {
		assertThat(saveWithRole("user", UserRole.USER).getRole()).isEqualTo(UserRole.USER);
		assertThat(saveWithRole("admin", UserRole.ADMIN).getRole()).isEqualTo(UserRole.ADMIN);
	}
}
