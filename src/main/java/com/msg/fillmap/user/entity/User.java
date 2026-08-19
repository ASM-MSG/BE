package com.msg.fillmap.user.entity;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

	/**
	 * 가입 시각. @CreationTimestamp 를 쓰지 않고 생성자에서 UTC 로 직접 넣는다 (MSG-376) —
	 * 그 애너테이션은 JVM 기본 존의 벽시계를 만들어 KST 개발 머신에서 +9h 가 저장되는데, 이 값은
	 * 응답에 실려 전역 코덱이 UTC 로 표기하므로 저장 축이 UTC 여야 한다.
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/**
	 * 동의 컬럼 7종은 전부 <b>읽기 전용 매핑</b>이다 (updatable = false, MSG-433 Codex P1). 쓰기 경로는
	 * 리포지토리의 native UPDATE 세 개(submitConsents · updateLocationConsent · updateMarketingConsent)
	 * 뿐이고, 엔티티에는 이 값들을 바꾸는 setter 도 상태 전이 메서드도 없다.
	 *
	 * <p>왜 매핑 수준에서 막는가 — 이 프로젝트는 @DynamicUpdate 를 쓰지 않아 더티 체킹이 <b>전 컬럼</b>을
	 * UPDATE 한다. 미동의 시점에 User 를 읽은 트랜잭션이 그 사이 커밋된 동의를 못 본 채 닉네임이나 프로필
	 * 이미지를 저장하면, 낡은 false·NULL 스냅숏이 동의 컬럼을 되덮어 철회 차단(§D-11)이 우회된다.
	 * updatable = false 면 그 컬럼들이 UPDATE 문에서 아예 빠져 우회가 구조적으로 불가능하다.
	 * INSERT 에는 그대로 포함되므로 신규 가입의 미동의 시작(자바 기본값 null·false)은 영향이 없다.
	 */
	@Column(name = "location_consent", nullable = false, updatable = false)
	private boolean locationConsent;

	/**
	 * 동의 시각(UTC). 한 번도 동의한 적 없으면 null 이고, 값이 실제로 달라질 때만 갱신된다 (FR-3·4).
	 * 2026-08-19 팀 합의로 위치 동의는 철회가 불가해져(FR-USER-14 개정) true 에서 되돌아갈 경로가 없다.
	 */
	@Column(name = "location_consent_changed_at", updatable = false)
	private LocalDateTime locationConsentChangedAt;

	/**
	 * 가입 약관 동의 중 철회가 없는 필수 3항목 (MSG-433 §D-2). 시각 컬럼 하나가 동의 여부와 시각을
	 * 겸한다 — null 이면 미동의, 값이 있으면 그 시각(UTC)에 동의다. 이 3항목의 철회는 계정 삭제라
	 * 값이 지워질 경로가 없고, boolean 을 따로 두면 여부와 시각이 어긋날 자리만 생긴다.
	 */
	@Column(name = "age_over14_consented_at", updatable = false)
	private LocalDateTime ageOver14ConsentedAt;

	@Column(name = "service_terms_consented_at", updatable = false)
	private LocalDateTime serviceTermsConsentedAt;

	@Column(name = "privacy_consented_at", updatable = false)
	private LocalDateTime privacyConsentedAt;

	/**
	 * 마케팅 정보 수신 동의 (선택 항목, MSG-433 §D-4). 켜고 끄기를 반복하므로 현재 상태와 마지막
	 * 변경 시각 쌍으로 둔다 — 시각 하나로는 "동의했다가 철회한 상태"를 표현할 수 없다.
	 * 신규 가입은 미동의로 시작한다 (자바 기본값 false = DB DEFAULT FALSE).
	 */
	@Column(name = "marketing_consent", nullable = false, updatable = false)
	private boolean marketingConsent;

	/** 마지막 동의·철회 시각(UTC). 한 번도 바꾼 적 없으면 null 이고, 값이 실제로 달라질 때만 갱신된다. */
	@Column(name = "marketing_consent_changed_at", updatable = false)
	private LocalDateTime marketingConsentChangedAt;

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
		// DB TIMESTAMP 정밀도(µs)로 절단 — 리눅스 나노초 시계에서 재조회 값과 어긋나지 않게
		this.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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

	/**
	 * 프로필 이미지 변경 (MSG-373). 저장 값은 S3 키가 아니라 그대로 열리는 공개 URL 이다 — 친구 축
	 * 응답 3종의 프로젝션이 이 컬럼 원문을 그대로 싣기 때문(§D-1). null 전달이 제거(기본 상태)다.
	 * 검증·URL 조립은 서비스 몫 — updateNickname 과 같이 엔티티는 전달값만 반영한다.
	 */
	public void changeProfileImage(String profileImageUrl) {
		this.profileImageUrl = profileImageUrl;
	}
}
