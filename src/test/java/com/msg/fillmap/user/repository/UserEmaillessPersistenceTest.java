package com.msg.fillmap.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;

/**
 * email 없는 카카오 유저 영속 (MSG-310 FR-1, 실 DB). V16(users.email NOT NULL 해제)이 실제로
 * 적용됐는지를 스키마 레벨에서 검증한다 — 유닛 테스트(OidcLoginServiceTest)는 리포지토리를 모킹해
 * 제약 위반을 못 잡는다. @Transactional 롤백 격리로 공유 로컬 DB 에 시드를 남기지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("email 없는 카카오 유저 영속 (V16, 실 DB)")
class UserEmaillessPersistenceTest {

	@Autowired
	private UserRepository userRepository;

	// 검증: FR-USER-04
	@Test
	@DisplayName("이메일 없이(null) 카카오 유저가 저장된다 — V16 NOT NULL 해제 검증")
	void 이메일_없이_카카오_유저가_저장된다() {
		User saved = userRepository.saveAndFlush(
			User.createOAuthUser(AuthProvider.KAKAO, "v16-" + UUID.randomUUID(), null, "이메일없음"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getEmail()).isNull();
	}
}
