package com.msg.fillmap.user.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.dto.AdminOrgAccountListResponseDto;
import com.msg.fillmap.user.dto.OrgAccountCreateRequestDto;
import com.msg.fillmap.user.dto.OrgAccountIssueResponseDto;
import com.msg.fillmap.user.dto.OrgAccountResendResponseDto;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 행사 운영자 계정 발급 코어 (MSG-499 FR-1·2). 승인 발급, 직접 발급, 초기 비밀번호 재발송이 여기 모인
 * "비밀번호 생성 → 해시 저장 → 커밋 후 발송 → 발송 실패 격리"를 공유한다.
 *
 * <p><b>발송은 트랜잭션 밖이다.</b> 안에 두면 SES 왕복이 DB 커넥션을 점유하고, 발송 성공 뒤 커밋이
 * 실패하면 비밀번호는 나갔는데 계정이 없는 역방향 구멍이 생긴다. 그래서 저장부
 * ({@link #createAccount}·{@link #replaceInitialPassword})와 발송부({@link #sendInitialPassword})가
 * 갈라져 있고, 호출부가 커밋 이후에 발송부를 부른다.
 *
 * <p>초기 비밀번호 평문은 이 클래스 밖으로 응답·로그·DB 어디에도 나가지 않는다 — 생성 직후 해시로
 * 저장하고 메일 본문에 한 번 실은 뒤 버린다. 발송 실패 로그에는 수신 주소와 예외 <b>타입</b>만 남는다
 * (로컬·dev 의 LoggingMailSender 가 본문을 찍는 것은 MSG-497 이 확정한 유일한 예외이고 prod 에서는
 * 그 빈이 뜰 수 없다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgAccountIssueService {

	/** 초기 비밀번호 알파벳 — 혼동 문자 0·O·1·l·I 를 뺀 대문자·소문자·숫자 (User.friendCode 선례). */
	private static final String PASSWORD_UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ";
	private static final String PASSWORD_LOWERCASE = "abcdefghijkmnopqrstuvwxyz";
	private static final String PASSWORD_DIGITS = "23456789";
	private static final String PASSWORD_ALPHABET = PASSWORD_UPPERCASE + PASSWORD_LOWERCASE + PASSWORD_DIGITS;
	private static final int PASSWORD_LENGTH = 16;
	private static final SecureRandom PASSWORD_RANDOM = new SecureRandom();

	/**
	 * 발급 시각은 사람이 읽는 안내라 KST 로 표기한다 — 저장·전송 축(UTC)과 무관한 메일 본문 표기다.
	 *
	 * <p>초 이하까지 찍는 이유는 이 값이 안내가 아니라 <b>복구 계약</b>이라서다. 여러 통을 받은 수신자가
	 * 어느 비밀번호가 유효한지 가리는 유일한 단서가 이 시각인데, 분 단위면 같은 분에 두 번 재발송된
	 * 두 메일이 같은 값을 달고 나가 구분이 불가능해진다. 재발송은 users 행 잠금으로 직렬화되므로 같은
	 * 초에 커밋될 수도 있어 마이크로초까지 찍는다(저장 정밀도와 같은 단위).
	 */
	private static final ZoneId MAIL_ZONE = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter MAIL_TIME_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
	private static final String LOGIN_URL = "https://fillmap.kr";
	private static final String MAIL_SUBJECT = "[필맵] 행사 운영자 계정 발급 안내";
	private static final String MAIL_BODY_FORMAT = """
		행사 운영자 계정이 발급되었습니다.

		로그인 주소: %s
		아이디: %s
		초기 비밀번호: %s
		발급 시각: %s (KST)

		보안을 위해 24시간 안에 비밀번호를 변경해 주세요. 첫 로그인 뒤 비밀번호를 바꾸기 전까지는
		행사 등재 콘솔을 이용할 수 없습니다.

		여러 통을 받으셨다면 발급 시각이 가장 늦은 메일의 비밀번호가 유효합니다.""";

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final MailSender mailSender;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	/** 프로덕션 생성자 — clock 을 UTC 로 고정해 Lombok 전체 생성자에 위임한다 (OrgAccountService 선례). */
	@Autowired
	public OrgAccountIssueService(UserRepository userRepository, PasswordEncoder passwordEncoder,
		MailSender mailSender, TransactionTemplate transactionTemplate) {
		this(userRepository, passwordEncoder, mailSender, transactionTemplate, Clock.systemUTC());
	}

	/**
	 * 직접 발급 (API 6) — 공문으로 먼저 확인된 기관이라 요청 행 없이 만든다. 승인(API 4)의 계정 생성과
	 * 커밋 후 발송을 그대로 공유하고 요청 행 전이만 없다.
	 *
	 * <p>응답을 받지 못했을 때의 복구는 같은 요청 재시도이고, 1409 가 오면 계정 목록(API 8)의 email
	 * 검색으로 가른다 — 결과가 있으면 발급 성공(크래시 전 커밋), 없으면 다른 계정과의 이메일 충돌이라
	 * 애초에 발급이 불가능했던 것이다.
	 */
	public OrgAccountIssueResponseDto issueDirect(OrgAccountCreateRequestDto request) {
		IssuedInitialPassword issued = transactionTemplate.execute(status ->
			createAccount(request.email(), request.contactName(), request.contactPhone(), request.orgName()));
		return new OrgAccountIssueResponseDto(issued.userId(), sendInitialPassword(issued));
	}

	/**
	 * 초기 비밀번호 재발송 (API 7). 대상은 아직 초기 로그인을 마치지 않은 ORG·LOCAL 계정뿐이다 —
	 * 이미 본인이 비밀번호를 바꾼 계정에 재발급하면 발급자가 다시 비밀번호를 아는 상태가 되어 행위자
	 * 특정(FR-21)의 목적이 무너진다. 사용 중 계정의 분실 복구는 이메일 재설정 흐름(MSG-497)이 맡는다.
	 *
	 * <p><b>사용자 행을 잠그고 그 안에서 mustChange 를 다시 확인하는 것이 계약이다.</b> 잠금 없이
	 * 선검사 후 교체하면, 사용자의 첫 비밀번호 변경이 먼저 커밋한 뒤 늦게 커밋한 재발송이 방금 정한
	 * 비밀번호를 무효화하고 플래그를 되세운다. 잠금 안 재검사면 그 경우 1423 으로 끝나 발송이 없다.
	 * 직렬화는 그 행을 쓰는 모든 경로가 같은 잠금을 거쳐야 성립하므로 본인 변경·재설정도 같은 잠금
	 * 조회를 쓴다(PasswordService).
	 */
	public OrgAccountResendResponseDto resendInitialPassword(Long userId) {
		IssuedInitialPassword issued = transactionTemplate.execute(status -> {
			User user = userRepository.findWithLockById(userId)
				.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
			if (user.getRole() != UserRole.ORG || user.getProvider() != AuthProvider.LOCAL
				|| !user.isPasswordMustChange()) {
				throw new ApiException(UserErrorCode.INITIAL_PASSWORD_RESEND_NOT_ALLOWED);
			}
			return replaceInitialPassword(user);
		});
		return new OrgAccountResendResponseDto(sendInitialPassword(issued));
	}

	/**
	 * 발급 계정 목록 (API 8). email 을 주면 완전 일치 필터이고, 결과 유무가 곧 직접 발급 성공 여부다
	 * (필터가 ORG·LOCAL 을 보장하므로 관리자가 필드를 눈으로 대조할 필요가 없다).
	 */
	@Transactional(readOnly = true)
	public AdminOrgAccountListResponseDto getAccounts(int page, int size, String email) {
		// PageRequest.of 에 그냥 넘기면 IllegalArgumentException 이 catch-all 핸들러에서 500 이 된다
		// (AdminReportServiceImpl 선례).
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE
			|| (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(UserErrorCode.INVALID_PAGE_RANGE);
		}
		PageRequest pageRequest = PageRequest.of(page, size);
		return AdminOrgAccountListResponseDto.from(email == null || email.isBlank()
			? userRepository.findAllByRoleAndProviderOrderByCreatedAtDesc(UserRole.ORG, AuthProvider.LOCAL,
				pageRequest)
			: userRepository.findAllByRoleAndProviderAndEmailOrderByCreatedAtDesc(UserRole.ORG, AuthProvider.LOCAL,
				email, pageRequest));
	}

	/**
	 * 계정 생성 (승인·직접 발급 공통). <b>호출부의 트랜잭션 안에서 실행된다</b> — 요청 행 잠금·상태 전이와
	 * 한 트랜잭션이어야 "계정만 생기고 요청은 대기로 남는" 어긋남이 없다.
	 *
	 * <p>이메일 중복은 선확인으로 거르고, 선확인과 삽입 사이의 경합은 flush 예외를 같은 1409 로 수렴시킨다
	 * (AuthService.signup 선례 그대로).
	 */
	IssuedInitialPassword createAccount(String email, String contactName, String contactPhone, String orgName) {
		if (userRepository.existsByEmail(email)) {
			throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
		}
		String plainPassword = generateInitialPassword();
		User user = User.createOrgUser(email, passwordEncoder.encode(plainPassword), contactName,
			contactPhone, orgName);
		try {
			// flush 를 당겨 INSERT 를 여기서 실행한다 — save 만 하면 위반이 커밋 시점에 터져 못 잡는다.
			User saved = userRepository.saveAndFlush(user);
			return new IssuedInitialPassword(saved.getId(), email, plainPassword, LocalDateTime.now(clock));
		} catch (DataIntegrityViolationException e) {
			throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS, e);
		}
	}

	/**
	 * 초기 비밀번호 교체 (재발송). 강제 변경 플래그는 유지된다 — 여전히 발급자가 아는 값이다.
	 * 호출부의 트랜잭션(사용자 행 잠금 보유) 안에서 실행된다.
	 */
	IssuedInitialPassword replaceInitialPassword(User user) {
		String plainPassword = generateInitialPassword();
		user.resetInitialPassword(passwordEncoder.encode(plainPassword));
		return new IssuedInitialPassword(user.getId(), user.getEmail(), plainPassword, LocalDateTime.now(clock));
	}

	/**
	 * 초기 비밀번호 발송 — <b>커밋 이후에 부른다.</b> 실패는 예외로 올라오지 않고 false 로 돌아온다
	 * (유틸은 실패를 예외로 올린다는 MSG-497 계약의 호출부 처리). 계정은 이미 발급됐고 복구 수단이
	 * 재발송 API 로 따로 있으므로, 발송 실패가 발급 자체를 실패로 뒤집지 않는다.
	 *
	 * <p>반환 true 의 의미는 <b>SES 접수까지</b>다. 배달 확인이 아니라서 오타 주소도 이 시점엔 true 이고
	 * 하드바운스는 몇 분 뒤에 온다.
	 */
	boolean sendInitialPassword(IssuedInitialPassword issued) {
		String issuedAtLabel = issued.issuedAt().atOffset(ZoneOffset.UTC)
			.atZoneSameInstant(MAIL_ZONE)
			.format(MAIL_TIME_FORMAT);
		try {
			mailSender.send(issued.email(), MAIL_SUBJECT,
				MAIL_BODY_FORMAT.formatted(LOGIN_URL, issued.email(), issued.plainPassword(), issuedAtLabel));
			return true;
		} catch (RuntimeException e) {
			// 예외를 로거에 넘기지 않고 타입만 남긴다. 발송 유틸에 넘긴 본문에 평문이 들어 있어서, 구현이나
			// SDK 가 그 본문을 예외 메시지에 실어 올리면 스택 트레이스를 통해 평문이 로그로 샌다 — 그때는
			// 평문 비노출(FR-2)이 이 한 줄에서 깨진다. 원인 추적에 필요한 신호(거부·스로틀 등)는 예외
			// 타입이 대부분 담고, 상세는 발송 구현 쪽 로그가 갖는다.
			log.error("[org-account] 초기 비밀번호 메일 발송 실패 — userId={}, to={}, cause={}",
				issued.userId(), issued.email(), e.getClass().getName());
			return false;
		}
	}

	/**
	 * 초기 비밀번호 16자. 로그인에는 정책 검증이 없지만 사용자가 보는 첫 비밀번호가 서비스 비밀번호
	 * 정책(영문·숫자 각 1자 이상)과 어긋난 모양이면 혼란만 주므로 같은 모양으로 만든다.
	 */
	private String generateInitialPassword() {
		while (true) {
			StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
			for (int i = 0; i < PASSWORD_LENGTH; i++) {
				password.append(PASSWORD_ALPHABET.charAt(PASSWORD_RANDOM.nextInt(PASSWORD_ALPHABET.length())));
			}
			// 16자 전부가 한쪽 부류일 확률은 사실상 0 이라 재생성 루프가 실제로 도는 일은 없다 —
			// 편향을 만드는 사후 치환(특정 자리를 숫자로 덮기) 대신 다시 뽑는 쪽을 골랐다.
			String candidate = password.toString();
			if (candidate.chars().anyMatch(Character::isLetter)
				&& candidate.chars().anyMatch(Character::isDigit)) {
				return candidate;
			}
		}
	}
}
