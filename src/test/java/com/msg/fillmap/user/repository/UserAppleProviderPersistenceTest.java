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
 * 애플 제공자 영속 (MSG-594, 실 DB). V1 의 chk_users_provider 가 LOCAL·KAKAO 만 허용하고 있어 V54 가
 * 제약을 재정의했는데, enum 상수만 늘려서는 DB CHECK 위반을 못 잡는다 — saveAndFlush 로 INSERT 를
 * 당겨 제약을 실제로 통과하는지 본다(UserRoleOrgPersistenceTest 와 같은 이유). 리프레시 토큰 암호문
 * 컬럼도 같은 마이그레이션이라 더티 체킹 저장과 스칼라 조회 왕복을 함께 본다.
 * @Transactional 롤백 격리로 공유 로컬 DB 에 계정을 남기지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("애플 제공자 영속 (V54, 실 DB)")
class UserAppleProviderPersistenceTest {

	@Autowired
	private UserRepository userRepository;

	private User appleUser() {
		return User.createOAuthUser(AuthProvider.APPLE, "001234." + UUID.randomUUID(), null, "애플유저");
	}

	// 검증: FR-AUTH-12, AC-594-13
	@Test
	@DisplayName("provider = APPLE 사용자가 저장된다 — V54 CHECK 재정의 검증")
	void APPLE_제공자_사용자_행이_CHECK_제약을_통과한다() {
		User saved = userRepository.saveAndFlush(appleUser());

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getProvider()).isEqualTo(AuthProvider.APPLE);
	}

	// 검증: FR-AUTH-12, AC-594-08
	@Test
	@DisplayName("저장한 암호문을 탈퇴 경로의 스칼라 조회로 읽는다 — 미저장 계정은 빈 Optional")
	void 저장한_애플_토큰_암호문을_스칼라로_읽는다() {
		User saved = userRepository.saveAndFlush(appleUser());
		assertThat(userRepository.findAppleRefreshTokenById(saved.getId())).isEmpty();

		saved.storeAppleRefreshToken("encrypted-base64");
		userRepository.flush();

		assertThat(userRepository.findAppleRefreshTokenById(saved.getId())).contains("encrypted-base64");
	}
}
