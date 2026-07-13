package com.msg.fillmap.user.entity;

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

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AuthProvider provider;

	@Column(length = 64)
	private String oid;

	@Column(nullable = false, length = 255, unique = true)
	private String email;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(name = "profile_image_url", columnDefinition = "text")
	private String profileImageUrl;

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
		this.emailVerified = false;
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
}
