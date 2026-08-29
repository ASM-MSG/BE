package com.msg.fillmap.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.auth.dto.PasswordChangeRequestDto;
import com.msg.fillmap.auth.dto.PasswordResetConfirmRequestDto;
import com.msg.fillmap.auth.dto.PasswordResetRequestDto;
import com.msg.fillmap.auth.dto.PasswordStatusResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.InvalidatedTokenStore;
import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.password.PasswordResetTokenStore;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.mail.MailProperties;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 비밀번호 상태 조회·변경·재설정 (MSG-497 FR-21·22).
 *
 * <p>DB 쓰기 경계를 {@code @Transactional} 이 아니라 {@link TransactionTemplate} 로 여는 곳이 둘 있다
 * (변경·재설정 확정). 두 흐름 모두 <b>커밋 이후</b>에 해야 하는 저장소 작업이 있고(잔여 링크 폐기,
 * 토큰 무효화와 세션 삭제), 커밋 <b>실패</b>에는 되돌릴 작업이 있어서다(선점한 토큰의 조건부 복원).
 * 같은 빈 안의 {@code @Transactional} 메서드 호출은 프록시를 타지 않아 경계가 서지 않고, 경계를
 * 세우려고 빈을 하나 더 만드는 것보다 여기서 시작·종료가 눈에 보이는 편이 낫다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

	/** 토큰 원문 — SecureRandom 32바이트를 base64url 로 인코딩한 43자 (User.friendCode 의 SecureRandom 선례). */
	private static final int TOKEN_BYTES = 32;
	private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
	private static final String MAIL_SUBJECT = "[필맵] 비밀번호 재설정 안내";
	private static final String MAIL_BODY_FORMAT = """
		비밀번호 재설정을 요청하셨습니다.

		아래 링크에서 새 비밀번호를 설정해 주세요. 링크는 30분 동안만 유효합니다.
		%s

		본인이 요청한 것이 아니라면 이 메일을 무시하셔도 됩니다.""";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final PasswordResetTokenStore passwordResetTokenStore;
	private final InvalidatedTokenStore invalidatedTokenStore;
	private final RefreshTokenService refreshTokenService;
	private final JwtProperties jwtProperties;
	private final MailSender mailSender;
	private final MailProperties mailProperties;
	private final TransactionTemplate transactionTemplate;

	/** 첫 로그인 게이트의 판정 재료. 비밀번호가 없는 소셜 계정은 플래그를 세울 경로가 없어 항상 false 다. */
	@Transactional(readOnly = true)
	public PasswordStatusResponseDto getStatus(Long userId) {
		return new PasswordStatusResponseDto(userRepository.existsByIdAndPasswordMustChangeTrue(userId));
	}

	/**
	 * 로그인 상태 비밀번호 변경 (FR-22). 강제 변경 플래그가 여기서 풀린다.
	 *
	 * <p>현재 비밀번호 대조를 요구하는 이유는 액세스 토큰 탈취만으로 비밀번호를 갈아탈 수 없게 하는
	 * 방어선이다. 기존 리프레시 세션은 유지한다 — 본인이 현재 비밀번호를 증명한 자발적 변경이라 복구
	 * 시나리오가 아니고, 첫 로그인 게이트 직후 재로그인을 강제하면 흐름이 끊긴다.
	 */
	public void changePassword(Long userId, PasswordChangeRequestDto request) {
		transactionTemplate.executeWithoutResult(status -> {
			// 잠금 조회다 (MSG-499) — 초기 비밀번호 재발송이 같은 행을 쓰므로, 여기서 잠그지 않으면 이
			// 트랜잭션의 낡은 스냅숏이 재발송 결과를 되덮는다(lost update). 잠근 뒤 현재 비밀번호를
			// 대조하므로, 재발송이 먼저 커밋했으면 사용자의 변경 요청은 2442 로 안전하게 실패한다.
			User user = userRepository.findWithLockById(userId)
				.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
			if (user.getProvider() != AuthProvider.LOCAL || user.getPasswordHash() == null) {
				// 비밀번호가 없는 계정의 요청을 "현재 비밀번호 불일치"로 수렴시키면 사용자가 비밀번호를
				// 다시 입력해 볼 뿐 원인을 알 수 없다.
				throw new ApiException(AuthErrorCode.PASSWORD_NOT_SET);
			}
			if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
				throw new ApiException(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
			}
			if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
				// 초기 비밀번호를 그대로 다시 쓰면 발급자가 여전히 아는 값이라 FR-21 의 목적이 무산된다.
				throw new ApiException(AuthErrorCode.NEW_PASSWORD_SAME_AS_CURRENT);
			}
			user.changePassword(passwordEncoder.encode(request.newPassword()));
			// 잔여 링크 폐기도 커밋 전이다. 비밀번호가 이미 바뀌었는데 옛 메일함의 링크가 TTL 까지 살아
			// 있으면 그 링크로 방금 정한 비밀번호를 덮을 수 있다 — 커밋 뒤에 폐기하면 Redis 장애가 정확히
			// 그 상태를 만들고 사용자에게는 500 만 남는다. 여기서 던지면 롤백돼 비밀번호도 링크도 그대로다
			// (fail-closed, 재시도 가능). 재설정 경로도 같은 원칙이다 — 토큰 소비가 저장보다 먼저이고,
			// 저장이 실패하면 조건부 복원으로 되돌린다.
			// 반대 경합(폐기는 됐는데 커밋 실패)은 비밀번호가 유지되고 링크만 죽어 재요청 한 번이면 된다.
			passwordResetTokenStore.revoke(userId);
		});
	}

	/**
	 * 재설정 링크 발송 요청 (FR-22). <b>어떤 경우에도 같은 성공 응답</b>이다 — 계정 부재·소셜 계정·
	 * 대상 밖 역할·쿨다운·발송 실패가 전부 조용히 발송 생략으로 수렴한다. 실패가 응답으로 새면 존재
	 * 계정만 다른 답을 돌려받아 계정 존재 오라클이 된다.
	 *
	 * <p>발송 대상을 ORG·ADMIN 의 LOCAL 계정으로 좁히는 이유: 운영의 이메일 로그인은 행사 운영자와
	 * 관리자 계정용이고(FR-AUTH-11), 재설정 후의 토큰 무효화 검사가 그 두 역할만 보기 때문이다.
	 * USER 역할 LOCAL 계정에까지 열면 "재설정해도 기존 액세스 토큰이 안 죽는" 구멍이 생긴다.
	 */
	public void requestReset(PasswordResetRequestDto request) {
		String email = request.email();
		if (!passwordResetTokenStore.tryAcquireCooldown(email)) {
			return;
		}
		Optional<User> found = userRepository.findByEmail(email);
		if (found.isEmpty() || !isResetTarget(found.get())) {
			return;
		}
		User user = found.get();
		String token = generateToken();
		passwordResetTokenStore.save(token, user.getId());
		sendResetMail(email, token, user.getId());
	}

	/**
	 * 재설정 확정 (FR-22). 토큰 소비 → 비밀번호 저장과 무효화 기록을 한 트랜잭션 → 커밋 후 기록 갱신과
	 * 세션 전량 삭제(둘 다 best-effort).
	 * <b>무효화 기록이 세션 삭제보다 먼저</b>인 것이 계약이다 — 재발급(reissue)의 "회전 save 후 검사"와
	 * 짝을 이뤄, 재설정과 재발급이 어떤 순서로 인터리빙돼도 옛 세션이 살아남지 않는다.
	 *
	 * <p>무효/만료/재사용 토큰은 전부 같은 2443 하나다. 사유를 가르면 토큰 상태가 오라클이 된다.
	 */
	public void resetPassword(PasswordResetConfirmRequestDto request) {
		Long userId = passwordResetTokenStore.consume(request.token());
		if (userId == null) {
			throw new ApiException(AuthErrorCode.INVALID_RESET_TOKEN);
		}
		try {
			transactionTemplate.executeWithoutResult(status -> {
				// 변경 경로와 같은 잠금 조회다 (MSG-499) — 비밀번호를 쓰는 세 경로(재발송·변경·재설정)가 전부
				// 같은 잠금을 거쳐야 낡은 스냅숏이 최신 커밋을 덮는 일이 사라진다. 어느 순서로 인터리빙돼도
				// 마지막 커밋의 해시와 강제 변경 플래그가 단일 진실이 된다.
				User user = userRepository.findWithLockById(userId)
					.orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_RESET_TOKEN));
				user.changePassword(passwordEncoder.encode(request.newPassword()));
				// 세션만 지우면 공격자가 이미 쥔 액세스 토큰이 잔여 수명(최대 1시간) 동안 산다.
				// 이 기록을 커밋 <b>전</b>에 찍는 이유: 커밋 뒤라면 Redis 장애가 "비밀번호는 바뀌었는데 옛
				// 액세스·리프레시가 전부 살아 있는" 상태를 만든다. 여기서 던지면 트랜잭션이 롤백되고 아래
				// 복원이 링크를 되살려 전체가 fail-closed(비밀번호 불변·재시도 가능)로 수렴한다.
				// 반대 경합(기록은 남았는데 커밋 실패)은 세션만 끊기고 비밀번호는 그대로라 보안 구멍이
				// 아니라 재로그인 불편이다.
				invalidatedTokenStore.invalidateUser(userId, Instant.now(), jwtProperties.refreshTokenTtl());
			});
		} catch (RuntimeException e) {
			// 조건부 복원 — 선점과 실패 사이에 재요청이 새 토큰을 발급했으면 그대로 둔다(스토어가 판정).
			passwordResetTokenStore.restore(request.token(), userId);
			throw e;
		}
		try {
			// 두 번째 기록 — 첫 기록과 커밋 사이에 구 비밀번호로 로그인이 끼어들면 그 토큰은 iat 가 첫
			// 기록보다 뒤여서 살아남는다. 무효화 경계를 커밋 시점까지 밀어 그 창을 닫는다. 실패해도 첫
			// 기록이 커밋 이전 발급분 전부를 덮으므로, 남는 위험은 "그 수 ms 창에 로그인이 끼는 것"과
			// "직후 Redis 장애"가 동시에 성립할 때뿐이다.
			invalidatedTokenStore.invalidateUser(userId, Instant.now(), jwtProperties.refreshTokenTtl());
			// 세션 전량 삭제도 이 자리다 — 실패해도 위 기록이 리프레시 수명만큼 살아 옛 리프레시의
			// 재발급을 거절한다(reissue 의 회전 save 후 검사).
			refreshTokenService.deleteAll(userId);
		} catch (RuntimeException e) {
			// 여기서 던지면 비밀번호는 이미 바뀌고 토큰은 소비된 뒤라 재시도가 불가능한 거짓 실패가 된다.
			log.warn("[password-reset] 커밋 후 세션 정리 실패 — 무효화 기록이 옛 토큰을 계속 막는다. userId={}",
				userId, e);
		}
	}

	private boolean isResetTarget(User user) {
		return user.getProvider() == AuthProvider.LOCAL
			&& user.getPasswordHash() != null
			&& (user.getRole() == UserRole.ORG || user.getRole() == UserRole.ADMIN);
	}

	private String generateToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		TOKEN_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * 발송 실패는 응답에 반영하지 않고 ERROR 로그로만 남긴다 — 은닉이 필요한 호출부가 잡는다는 규칙의
	 * 적용 지점이다(유틸은 실패를 예외로 올린다). 로그에 토큰 원문과 링크를 남기지 않는다.
	 */
	private void sendResetMail(String email, String token, Long userId) {
		String link = mailProperties.resetLinkBaseUrl() + "?token=" + token;
		try {
			mailSender.send(email, MAIL_SUBJECT, MAIL_BODY_FORMAT.formatted(link));
		} catch (RuntimeException e) {
			log.error("[password-reset] 재설정 메일 발송 실패 — userId={}", userId, e);
		}
	}
}
