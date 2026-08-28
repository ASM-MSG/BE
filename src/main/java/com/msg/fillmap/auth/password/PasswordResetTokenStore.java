package com.msg.fillmap.auth.password;

/**
 * 비밀번호 재설정 토큰 저장소 계약 (MSG-497). 토큰 원문은 저장하지 않는다 — 구현이 SHA-256 해시로
 * 바꿔 키에 넣으므로 저장소가 새어도 메일의 링크를 재구성할 수 없다(RedisInvalidatedTokenStore 선례).
 *
 * <p><b>사용자당 활성 토큰은 1개다.</b> 정방향(해시 → userId)과 역방향(userId → 해시) 두 키가 쌍이고,
 * {@link #save} 가 역방향에 남은 이전 해시로 옛 정방향 키를 지운다. 재요청하면 이전 링크가 그 즉시
 * 죽는다 — 토큰별 독립 키만 두면 재요청마다 이전 링크가 TTL 까지 유효한 채 메일함에 쌓인다(회수 불가).
 *
 * <p>만료 판정은 저장소 TTL 이 전담한다. 서버 코드에 시각 비교가 없어 인자 없는 {@code now()} 금지
 * 컨벤션(MSG-376)의 위반 지점 자체가 생기지 않는다.
 */
public interface PasswordResetTokenStore {

	/** 발급·교체. 같은 사용자에게 남아 있던 이전 토큰을 이 시점에 즉시 무효화하고 새 토큰으로 바꾼다. */
	void save(String token, Long userId);

	/**
	 * 1회성 소비(원자 선점) — 동시 요청 둘 중 하나만 userId 를 받는다.
	 *
	 * @return 토큰 주인의 userId, 없거나(위조·만료) 이미 쓰인 토큰이면 null
	 */
	Long consume(String token);

	/** 비밀번호 변경 성공이 부르는 폐기 — 옛 메일함의 링크가 TTL 까지 살아 있으면 안 된다. */
	void revoke(Long userId);

	/**
	 * 선점한 토큰의 조건부 복원 (reset 의 DB 실패 경로 전용). 역방향 키가 <b>비어 있을 때만</b>
	 * 되살린다 — 선점과 복원 사이에 재요청이 새 토큰을 발급했으면 그대로 두고 no-op 으로 끝낸다.
	 * 무조건 복원이면 사용자가 방금 받은 새 링크를 실패한 옛 링크로 되덮는다.
	 */
	void restore(String token, Long userId);

	/**
	 * 이메일당 발송 쿨다운 선점 (60초). 비로그인 공개 API 가 메일 발송(SES 비용·수신자 폭탄)을
	 * 유발하는 것을 막는 최소 가드다. 초과 요청도 응답은 같은 성공이라 계정 존재 은닉은 유지된다.
	 *
	 * @return 선점 성공(발송 가능)이면 true, 쿨다운 중이면 false
	 */
	boolean tryAcquireCooldown(String email);
}
