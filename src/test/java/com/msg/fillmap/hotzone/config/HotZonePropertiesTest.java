package com.msg.fillmap.hotzone.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HotZoneProperties")
class HotZonePropertiesTest {

	@Test
	void topK가_0이면_기동에_실패한다() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new HotZoneProperties(0, 3))
			.withMessageContaining("top-k");
	}

	@Test
	void topK가_음수면_기동에_실패한다() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new HotZoneProperties(-1, 3))
			.withMessageContaining("top-k");
	}
}
