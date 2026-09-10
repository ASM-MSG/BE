package com.msg.fillmap.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 애플 리프레시 토큰 암호화 (MSG-594 D-3). 형식 계약은 Base64(IV 12바이트 ∥ 암호문 ∥ GCM 태그 16바이트)이고
 * 키는 Base64 32바이트다. 키 로테이션은 미지원이라 키 하나로 왕복만 본다.
 */
@DisplayName("애플 리프레시 토큰 암호화")
class AppleRefreshTokenCipherTest {

	private static final int IV_LENGTH = 12;
	private static final int GCM_TAG_LENGTH = 16;
	// openssl rand -base64 32 형태의 테스트 전용 키 — 실제 값이 아니다.
	private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private static AppleRefreshTokenCipher cipherWithKey(String key) {
		return new AppleRefreshTokenCipher(new AppleOidcProperties(
			"https://appleid.apple.com", "https://appleid.apple.com/auth/keys",
			"https://appleid.apple.com/auth/token", "https://appleid.apple.com/auth/revoke",
			"kr.fillmap.app", null, null, null, key));
	}

	// 검증: FR-AUTH-12, AC-594-08
	@Test
	@DisplayName("암호화한 값을 복호화하면 원문이다 — 출력은 IV 12바이트 ∥ 암호문 ∥ 태그 16바이트의 Base64")
	void 암호화한_값을_복호화하면_원문이다() {
		AppleRefreshTokenCipher cipher = cipherWithKey(TEST_KEY);
		String plain = "r1234567890abcdef.0.apple-refresh-token";

		String encrypted = cipher.encrypt(plain);

		assertThat(encrypted).doesNotContain(plain);
		assertThat(Base64.getDecoder().decode(encrypted))
			.hasSize(IV_LENGTH + plain.getBytes(StandardCharsets.UTF_8).length + GCM_TAG_LENGTH);
		assertThat(cipher.decrypt(encrypted)).isEqualTo(plain);
	}

	// 검증: FR-AUTH-12, AC-594-08
	@Test
	@DisplayName("같은 원문도 호출마다 다른 암호문이 된다 — IV 가 SecureRandom 무작위")
	void 같은_원문도_호출마다_다른_암호문이_된다() {
		AppleRefreshTokenCipher cipher = cipherWithKey(TEST_KEY);

		String first = cipher.encrypt("same-token");
		String second = cipher.encrypt("same-token");

		assertThat(first).isNotEqualTo(second);
		assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
	}

	// 검증: FR-AUTH-12, AC-594-08
	@Test
	@DisplayName("변조된 암호문은 복호화에 실패한다 — GCM 인증 태그가 잡는다")
	void 변조된_암호문은_복호화에_실패한다() {
		AppleRefreshTokenCipher cipher = cipherWithKey(TEST_KEY);
		byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("token-to-tamper"));
		bytes[IV_LENGTH] ^= 0x01;	// 암호문 첫 바이트 한 비트 반전
		String tampered = Base64.getEncoder().encodeToString(bytes);

		assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
	}

	// 검증: FR-AUTH-12, AC-594-08
	@Test
	@DisplayName("32바이트가 아닌 키는 생성(기동) 시 거절된다 — 16바이트면 AES 가 조용히 128비트로 내려앉기 때문")
	void 키가_32바이트가_아니면_생성_시_실패한다() {
		String key16 = Base64.getEncoder().encodeToString(new byte[16]);

		assertThatThrownBy(() -> cipherWithKey(key16))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32바이트");
	}

	// 검증: FR-AUTH-12, D-10
	@Test
	@DisplayName("키가 비어 있으면 첫 호출에서 실패한다 — 생성(기동)은 통과해 로컬 부팅을 막지 않는다")
	void 키가_비어_있으면_첫_호출에서_실패한다() {
		AppleRefreshTokenCipher cipher = cipherWithKey("");

		assertThatThrownBy(() -> cipher.encrypt("token"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("애플 토큰 암호화 키 미설정");
		assertThatThrownBy(() -> cipher.decrypt("aGVsbG8="))
			.isInstanceOf(IllegalStateException.class);
	}
}
