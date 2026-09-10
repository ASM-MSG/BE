package com.msg.fillmap.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GridCursor opaque 커서 인코딩")
class GridCursorTest {

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	// 검증: FR-GRID-08
	@Test
	@DisplayName("커서는 gridY_gridX 를 Base64URL 로 왕복 인코딩한다")
	void 커서는_gridY_gridX를_Base64URL로_왕복인코딩한다() {
		String token = GridCursor.encode(41643, 110460);

		// MSG-90 스펙 예시: "41643_110460" → NDE2NDNfMTEwNDYw
		assertThat(token).isEqualTo("NDE2NDNfMTEwNDYw");

		GridCursor decoded = GridCursor.decode(token);
		assertThat(decoded.gridY()).isEqualTo(41643);
		assertThat(decoded.gridX()).isEqualTo(110460);
	}

	// 검증: FR-GRID-09
	@Test
	@DisplayName("형식이 틀린 커서 디코드는 예외다")
	void 형식이_틀린_커서_디코드는_예외다() {
		// Base64 불량
		assertThatThrownBy(() -> GridCursor.decode("!!!not-base64!!!"))
			.isInstanceOf(IllegalArgumentException.class);
		// 구분자 없음
		assertThatThrownBy(() -> GridCursor.decode(base64Url("41643110460")))
			.isInstanceOf(IllegalArgumentException.class);
		// 정수 아님
		assertThatThrownBy(() -> GridCursor.decode(base64Url("abc_def")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
