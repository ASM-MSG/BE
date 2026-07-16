package com.msg.fillmap.grid;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * viewport keyset 커서 (MSG-90). 마지막으로 내려준 항목의 (gridY, gridX)를
 * Base64URL("{gridY}_{gridX}") 로 감싼 opaque 토큰이다 — FE 는 토큰을 그대로 되돌려주기만 한다.
 * 내부 파싱 규칙은 GridEncoder 와 동일한 "{y}_{x}" 를 재사용한다.
 * 디코드 실패(Base64 불량·구분자 없음·정수 아님)는 IllegalArgumentException 으로 표면화한다
 * — 서비스가 GridErrorCode.INVALID_CURSOR 로 변환한다.
 */
public record GridCursor(long gridY, long gridX) {

	public static String encode(long gridY, long gridX) {
		String raw = gridY + "_" + gridX;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static GridCursor decode(String token) {
		byte[] decoded = Base64.getUrlDecoder().decode(token);
		String raw = new String(decoded, StandardCharsets.UTF_8);
		int separator = raw.indexOf('_');
		if (separator < 0) {
			throw new IllegalArgumentException("커서에 구분자가 없습니다: " + raw);
		}
		try {
			long gridY = Long.parseLong(raw.substring(0, separator));
			long gridX = Long.parseLong(raw.substring(separator + 1));
			return new GridCursor(gridY, gridX);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("커서 좌표가 정수가 아닙니다: " + raw, e);
		}
	}
}
