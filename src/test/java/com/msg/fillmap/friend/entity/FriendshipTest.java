package com.msg.fillmap.friend.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Friendship·FriendshipId 엔티티 단위 (MSG-185). UserGridTest 패턴 미러 — 복합키 동등성 계약과
 * 상태 전이를 검증한다. equals/hashCode 는 영속성 컨텍스트 키 비교의 기반이라 분기 전체를 밟는다.
 */
@DisplayName("Friendship 엔티티")
class FriendshipTest {

	@Test
	@DisplayName("같은 방향·같은 유저 쌍의 복합키는 동등하다")
	void 같은_방향의_같은_유저_쌍_복합키는_동등하다() {
		FriendshipId id = new FriendshipId(1L, 2L);

		assertThat(id).isEqualTo(id);
		assertThat(id).isEqualTo(new FriendshipId(1L, 2L));
		assertThat(id).hasSameHashCodeAs(new FriendshipId(1L, 2L));
	}

	@Test
	@DisplayName("방향이 다르거나 한쪽 id 가 다르면 동등하지 않다 — 역방향은 별개 키")
	void 방향이_다르거나_한쪽이_다르면_동등하지_않다() {
		FriendshipId id = new FriendshipId(1L, 2L);

		assertThat(id).isNotEqualTo(new FriendshipId(2L, 1L));
		assertThat(id).isNotEqualTo(new FriendshipId(1L, 3L));
		assertThat(id).isNotEqualTo(null);
		assertThat(id).isNotEqualTo("1_2");
	}

	// 검증: FR-FRIEND-02
	@Test
	@DisplayName("요청 생성은 PENDING 이고 응답 시각이 없다 (FR-4)")
	void 요청_생성은_PENDING이고_응답_시각이_없다() {
		Friendship friendship = Friendship.request(1L, 2L);

		assertThat(friendship.getRequesterId()).isEqualTo(1L);
		assertThat(friendship.getAddresseeId()).isEqualTo(2L);
		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.PENDING);
		assertThat(friendship.getRespondedAt()).isNull();
	}

	// 검증: FR-FRIEND-04
	@Test
	@DisplayName("수락하면 ACCEPTED 로 승격되고 응답 시각이 기록된다 (FR-10)")
	void 수락하면_ACCEPTED로_승격되고_응답_시각이_기록된다() {
		Friendship friendship = Friendship.request(1L, 2L);

		friendship.accept();

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
		assertThat(friendship.getRespondedAt()).isNotNull();
	}
}
