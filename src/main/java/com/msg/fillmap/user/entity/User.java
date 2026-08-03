package com.msg.fillmap.user.entity;

import java.security.SecureRandom;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	// 친구 코드 알파벳 — 혼동 문자 I·O·0·1 제외 32종. V18 백필 SQL 과 동일 문자셋 (MSG-185 §D1).
	private static final String FRIEND_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int FRIEND_CODE_LENGTH = 8;
	private static final SecureRandom FRIEND_CODE_RANDOM = new SecureRandom();

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AuthProvider provider;

	@Column(length = 64)
	private String oid;

	// 카카오 가입은 email 을 받지 않아 null 이다 (MSG-310, V16 에서 NOT NULL 해제). UNIQUE 는 NULL 중복 허용.
	@Column(length = 255, unique = true)
	private String email;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(name = "profile_image_url", columnDefinition = "text")
	private String profileImageUrl;

	// 고정 친구 코드 — 가입 시 생성자에서 부여, 전역 유일, 재발급 없음(후속) (V18, MSG-185 §D2).
	@Column(name = "friend_code", nullable = false, unique = true, length = 8)
	private String friendCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "grid_color", nullable = false, length = 10)
	private GridColor gridColor;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private UserRole role;

	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	@Column(name = "last_login_at")
	private LocalDateTime lastLoginAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder(access = AccessLevel.PRIVATE)
	private User(AuthProvider provider, String oid, String email, String passwordHash, String nickname, UserRole role) {
		this.provider = provider;
		this.oid = oid;
		this.email = email;
		this.passwordHash = passwordHash;
		this.nickname = nickname;
		this.role = role;
		this.gridColor = GridColor.BLUE;
		this.emailVerified = false;
		this.friendCode = generateFriendCode();
	}

	// 생성자 한 곳이 팩토리 2개(LOCAL·OAuth)를 전부 커버한다 — 가입 경로별 생성 코드 불요 (§D2).
	// ponytail: 충돌 재시도 없음 — 32^8(≈1.1조) 공간에서 확률 ~1e-9, DB UNIQUE(uq_users_friend_code)가
	// 백스톱. 실측 충돌이 나오면 가입 서비스에 재시도 루프 추가.
	private static String generateFriendCode() {
		StringBuilder code = new StringBuilder(FRIEND_CODE_LENGTH);
		for (int i = 0; i < FRIEND_CODE_LENGTH; i++) {
			code.append(FRIEND_CODE_ALPHABET.charAt(FRIEND_CODE_RANDOM.nextInt(FRIEND_CODE_ALPHABET.length())));
		}
		return code.toString();
	}

	public static User createLocalUser(String email, String encodedPassword, String nickname) {
		return User.builder()
			.provider(AuthProvider.LOCAL)
			.email(email)
			.passwordHash(encodedPassword)
			.nickname(nickname)
			.role(UserRole.USER)
			.build();
	}

	public static User createOAuthUser(AuthProvider provider, String oid, String email, String nickname) {
		return User.builder()
			.provider(provider)
			.oid(oid)
			.email(email)
			.nickname(nickname)
			.role(UserRole.USER)
			.build();
	}

	/** 닉네임 변경 (MSG-203). 길이 검증은 요청 DTO 몫 — 엔티티는 전달값을 그대로 반영한다(더티 체킹 UPDATE). */
	public void updateNickname(String nickname) {
		this.nickname = nickname;
	}
}
