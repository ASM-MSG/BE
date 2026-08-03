package com.msg.fillmap.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 친구 코드 생성 (MSG-185 §D2, 엔티티 단위). 생성자 내부화라 팩토리 2개(LOCAL·OAuth) 어느 쪽으로
 * 만들어도 코드가 부여돼야 한다. 유일성은 DB UNIQUE(uq_users_friend_code) 몫이라 여기선 형식만 본다.
 */
@DisplayName("User 친구 코드 생성")
class UserFriendCodeTest {

	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	@Test
	@DisplayName("가입한 사용자는 8자 친구 코드를 가진다 — LOCAL·OAuth 양쪽 (FR-2)")
	void 가입한_사용자는_8자_친구_코드를_가진다() {
		User local = User.createLocalUser("local@fillmap.dev", "hash", "로컬유저");
		User oauth = User.createOAuthUser(AuthProvider.KAKAO, "kakao-oid", null, "카카오유저");

		assertThat(local.getFriendCode()).hasSize(8);
		assertThat(oauth.getFriendCode()).hasSize(8);
	}

	@Test
	@DisplayName("친구 코드는 허용 알파벳 32종만 사용한다 — 혼동 문자 I·O·0·1 배제 (§D1)")
	void 친구_코드는_허용_알파벳_32종만_사용한다() {
		// 8자 × 100명 = 800자 표본 — 32종 외 문자가 한 번이라도 나오면 실패한다.
		for (int i = 0; i < 100; i++) {
			String code = User.createLocalUser("u" + i + "@fillmap.dev", "hash", "유저").getFriendCode();

			assertThat(code).matches("[" + ALPHABET + "]{8}");
		}
	}
}
