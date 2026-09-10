package com.msg.fillmap.auth.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.OidcLoginRequestDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.oidc.AppleOidcIdTokenVerifier;
import com.msg.fillmap.auth.oidc.AppleRefreshTokenCipher;
import com.msg.fillmap.auth.oidc.AppleTokenClient;
import com.msg.fillmap.auth.oidc.OidcIdTokenVerifier;
import com.msg.fillmap.auth.oidc.OidcUserInfo;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 소셜(OIDC) 로그인 (MSG-135, 애플 확장 MSG-594).
 *
 * <p><b>트랜잭션 경계</b>: {@link #login} 은 트랜잭션이 없는 오케스트레이션이다 — ID 토큰 검증(JWKS 조회), 존재
 * 조회, 애플 인가 코드 교환, sub 대조, 암호화를 전부 밖에서 끝내고 쓰기(find-or-create·토큰 저장·리프레시 발급)만
 * {@link #issue} 에 모아 {@link TransactionTemplate} 경계 하나로 감싼다(D-8). 외부 왕복이 DB 커넥션을 점유하지
 * 않게 하기 위해서이고, {@code @Transactional} 대신 템플릿인 이유는 자기 호출 함정이다: 같은 클래스 안에서
 * {@code login} 이 {@code @Transactional} 붙은 {@link #issueForOidcUser} 를 부르면 프록시를 거치지 않아
 * 트랜잭션이 열리지 않는다(PasswordService 선례). {@code issueForOidcUser} 는 DevAuthController 가 다른 빈에서
 * 프록시를 통해 부르므로 기존 {@code @Transactional} 이 정상으로 걸린다.
 */
@Slf4j
@Service
public class OidcLoginService {

	private static final int NICKNAME_MIN_LENGTH = 2;
	private static final int NICKNAME_MAX_LENGTH = 20;
	private static final String DEFAULT_NICKNAME_PREFIX = "필맵러";

	private final UserRepository userRepository;
	private final TokenProvider tokenProvider;
	private final RefreshTokenService refreshTokenService;
	private final Map<AuthProvider, OidcIdTokenVerifier> verifiers;
	private final AppleTokenClient appleTokenClient;
	private final AppleRefreshTokenCipher appleRefreshTokenCipher;
	// 검증기 맵은 인터페이스라 verifySubject 가 없다 — 교환 응답 id_token 의 sub 대조용으로 구체 타입을 한 번 더 받는다
	private final AppleOidcIdTokenVerifier appleOidcIdTokenVerifier;
	private final TransactionTemplate transactionTemplate;

	public OidcLoginService(
		UserRepository userRepository,
		TokenProvider tokenProvider,
		RefreshTokenService refreshTokenService,
		List<OidcIdTokenVerifier> verifiers,
		AppleTokenClient appleTokenClient,
		AppleRefreshTokenCipher appleRefreshTokenCipher,
		AppleOidcIdTokenVerifier appleOidcIdTokenVerifier,
		TransactionTemplate transactionTemplate
	) {
		this.userRepository = userRepository;
		this.tokenProvider = tokenProvider;
		this.refreshTokenService = refreshTokenService;
		this.verifiers = verifiers.stream()
			.collect(Collectors.toUnmodifiableMap(OidcIdTokenVerifier::supports, Function.identity()));
		this.appleTokenClient = appleTokenClient;
		this.appleRefreshTokenCipher = appleRefreshTokenCipher;
		this.appleOidcIdTokenVerifier = appleOidcIdTokenVerifier;
		this.transactionTemplate = transactionTemplate;
	}

	/** ID 토큰만 있는 경로(웹 카카오 인가 코드 로그인) — DTO 오버로드에 위임한다. */
	public LoginResponseDto login(AuthProvider provider, String idToken, String deviceId) {
		return login(provider, new OidcLoginRequestDto(idToken, null, null, null), deviceId);
	}

	/**
	 * 트랜잭션 없음 — 클래스 Javadoc의 경계 설명 참조. 애플 첫 로그인의 교환 실패(2423·2502)와 sub 불일치(2423)는
	 * 쓰기 경계에 들어가기 전에 전파되므로 계정도 토큰도 저장되지 않는다(fail-closed, D-8).
	 */
	public LoginResponseDto login(AuthProvider provider, OidcLoginRequestDto request, String deviceId) {
		OidcIdTokenVerifier verifier = verifiers.get(provider);
		if (verifier == null) {
			throw new ApiException(AuthErrorCode.UNSUPPORTED_PROVIDER);
		}

		OidcUserInfo info = verifier.verify(request.idToken(), request.nonce());
		String encryptedAppleToken = provider == AuthProvider.APPLE ? exchangeAppleTokenIfNew(request, info) : null;
		return transactionTemplate.execute(
			status -> issue(provider, info, deviceId, request.fullName(), encryptedAppleToken));
	}

	/**
	 * 애플 첫 로그인의 인가 코드 교환 — 트랜잭션 밖. 계정이 이미 있으면 교환도 애플 왕복도 없다(AC-594-04).
	 * 존재 조회는 리포지토리 자체의 readOnly 경계에서 실행되고 바로 닫힌다. 같은 sub 의 첫 로그인 두 요청이 겹쳐
	 * 둘 다 교환에 성공해도 쓰기 경계 안의 ON CONFLICT 가 한 행만 만들고 마지막 암호문이 남는다(무해한 경합).
	 *
	 * @return 암호화한 애플 리프레시 토큰, 계정이 이미 있으면 null
	 */
	private String exchangeAppleTokenIfNew(OidcLoginRequestDto request, OidcUserInfo info) {
		if (request.authorizationCode() == null || request.authorizationCode().isBlank()) {
			// DB 접근 전에 끊는다 — 교환할 코드가 없으면 첫 로그인이든 아니든 심사 요건(탈퇴 시 취소)을 못 채운다
			log.warn("애플 로그인 요청에 authorizationCode 없음 — 2423 으로 수렴");
			throw new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
		}
		if (userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid()).isPresent()) {
			return null;
		}
		AppleTokenClient.Exchanged exchanged = appleTokenClient.exchange(request.authorizationCode());
		// 요청 idToken 과 authorizationCode 가 서로 다른 애플 계정에서 나온 조합을 막는다 — 없으면 A 계정 행에 B 의
		// 리프레시 토큰이 저장되고 A 가 탈퇴할 때 B 의 애플 연결이 취소된다(Codex P2). 교환은 이미 끝났지만 저장하지 않는다.
		if (!info.oid().equals(appleOidcIdTokenVerifier.verifySubject(exchanged.idToken()))) {
			log.warn("애플 교환 응답 id_token 의 sub 가 요청 ID 토큰과 불일치 — 2423 으로 수렴");
			throw new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
		}
		return appleRefreshTokenCipher.encrypt(exchanged.refreshToken());
	}

	/**
	 * OIDC 사용자 정보로 find-or-create 후 액세스+리프레시 토큰을 발급한다 (MSG-135).
	 * [로컬/dev] 소셜 로그인 모의(DevAuthController) 전용 — 다른 빈에서 프록시를 통해 불리므로 트랜잭션이 걸린다.
	 * 교환을 모르므로 APPLE 이어도 애플 왕복·서명 키가 필요 없다(FR-12).
	 */
	@Transactional
	public LoginResponseDto issueForOidcUser(AuthProvider provider, OidcUserInfo info, String deviceId) {
		return issue(provider, info, deviceId, null, null);
	}

	/**
	 * 쓰기 경계 — 열린 트랜잭션 안에서만 호출한다({@link #login} 은 템플릿 안, {@link #issueForOidcUser} 는 프록시 안).
	 *
	 * @param fullName            애플이 첫 승인에만 주는 이름 — 계정을 새로 만들 때만 닉네임 후보(이미 있는 계정은 무시)
	 * @param encryptedAppleToken 애플 리프레시 토큰 암호문 — 있으면 더티 체킹 UPDATE 로 저장(마지막 쓰기가 남는다)
	 */
	private LoginResponseDto issue(AuthProvider provider, OidcUserInfo info, String deviceId, String fullName,
		String encryptedAppleToken) {
		User user = userRepository.findByProviderAndOid(provider, info.oid())
			.orElseGet(() -> registerNewUser(provider, info, resolveNickname(info.nickname(), fullName)));
		if (encryptedAppleToken != null) {
			user.storeAppleRefreshToken(encryptedAppleToken);
		}

		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenService.issue(user.getId(), deviceId);
		return new LoginResponseDto(accessToken, refreshToken, user.getRole().name());
	}

	private User registerNewUser(AuthProvider provider, OidcUserInfo info, String nickname) {
		// email 은 카카오에서 받지 않아 null 일 수 있다 (MSG-310) — null 이면 중복 검사 없이 그대로 저장한다.
		if (info.email() != null && userRepository.existsByEmail(info.email())) {
			throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
		}
		// 동시 첫 로그인 경합 (Codex 지적): 두 요청이 모두 부재를 관측해도 ON CONFLICT 무삽입이라
		// UNIQUE 위반 500 이 없다 — 삽입 후 재조회가 승자 행이면 그걸로 토큰을 발급한다(패자 회수).
		// 팩토리를 거치는 건 friend_code 생성 로직을 엔티티 한 곳에 유지하기 위해서다 (NOT NULL, DB DEFAULT 없음).
		User candidate = User.createOAuthUser(provider, info.oid(), info.email(), nickname);
		userRepository.insertOAuthUserIgnoreConflict(
			provider.name(), info.oid(), info.email(), nickname, candidate.getFriendCode());
		return userRepository.findByProviderAndOid(provider, info.oid())
			// oid 재조회 부재 = 삽입이 email 충돌로 무효된 것 (다른 계정이 같은 이메일을 선점)
			.orElseThrow(() -> new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS));
	}

	/**
	 * 가입 닉네임 — 제공자 공통 규칙 (D-12). 제공자 클레임(카카오 nickname) → fullName(공백 정리 후 2~20자일 때만,
	 * D-11) → "필맵러" + 4자리 숫자(D-2). 카카오 토큰에 nickname 클레임이 없어 NOT NULL 삽입이 500 으로 터지던
	 * 잠재 결함도 같은 규칙으로 닫힌다. 닉네임은 중복 허용(FR-USER-03)이라 충돌 검사는 없다.
	 */
	static String resolveNickname(String claimNickname, String fullName) {
		if (claimNickname != null && !claimNickname.isBlank()) {
			return claimNickname;
		}
		if (fullName != null) {
			String stripped = fullName.strip();
			if (stripped.length() >= NICKNAME_MIN_LENGTH && stripped.length() <= NICKNAME_MAX_LENGTH) {
				return stripped;
			}
		}
		// 보안 값이 아니라 ThreadLocalRandom 이면 충분하다
		return DEFAULT_NICKNAME_PREFIX + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
	}
}
