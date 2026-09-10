package com.msg.fillmap.auth.oidc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.crypto.encrypt.AesBytesEncryptor;
import org.springframework.security.crypto.encrypt.AesBytesEncryptor.CipherAlgorithm;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Component;

/**
 * 애플 리프레시 토큰의 저장용 암호화 (MSG-594 D-3). AES-256-GCM, IV 12바이트 무작위, 출력은
 * Base64(IV ∥ 암호문 ∥ 태그). 이미 클래스패스에 있는 spring-security-crypto 의 AesBytesEncryptor 가
 * 정확히 이 형식(IV 를 앞에 붙이고 GCM 태그는 Cipher 가 뒤에 붙인다)이라 신규 의존성 없이 감싼다.
 *
 * <p>키가 비어 있으면 생성은 통과하고 첫 호출에서 실패한다 — 키 없는 로컬 개발자의 부팅을 막지 않기
 * 위해서다(D-10). 키 로테이션은 미지원 — 재암호화 배치가 필요해지면 그때 도입한다.
 */
@Component
public class AppleRefreshTokenCipher {

	private static final int IV_LENGTH = 12;
	private static final int KEY_LENGTH = 32;

	// null = 키 미설정. 비어 있는 것만 허용하고 잘못된 Base64 는 기동 실패다. 길이는 직접 검사한다 —
	// SecretKeySpec 은 길이를 안 보고 AES 는 16·24·32 를 다 받아, 16바이트 키가 조용히 AES-128 로 내려앉는다.
	private final AesBytesEncryptor encryptor;

	public AppleRefreshTokenCipher(AppleOidcProperties properties) {
		String key = properties.tokenEncryptionKey();
		this.encryptor = (key == null || key.isBlank()) ? null : new AesBytesEncryptor(
			new SecretKeySpec(decodeKey(key), "AES"), KeyGenerators.secureRandom(IV_LENGTH), CipherAlgorithm.GCM);
	}

	private static byte[] decodeKey(String key) {
		byte[] raw = Base64.getDecoder().decode(key.strip());
		if (raw.length != KEY_LENGTH) {
			throw new IllegalStateException("애플 토큰 암호화 키는 32바이트여야 한다 — openssl rand -base64 32");
		}
		return raw;
	}

	public String encrypt(String plain) {
		return Base64.getEncoder().encodeToString(encryptor().encrypt(plain.getBytes(StandardCharsets.UTF_8)));
	}

	/** 변조된 암호문(GCM 태그 불일치)은 IllegalStateException 이다 — 호출자(탈퇴 best-effort)가 감싼다. */
	public String decrypt(String encrypted) {
		return new String(encryptor().decrypt(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
	}

	private AesBytesEncryptor encryptor() {
		if (encryptor == null) {
			throw new IllegalStateException("애플 토큰 암호화 키 미설정 — oauth.apple.token-encryption-key");
		}
		return encryptor;
	}
}
