package com.msg.fillmap.badge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * V33 뱃지 그림 주소 검증 (실 PostGIS, MSG-405 — 스펙 MSG-363 "그림" 시나리오 2건).
 * 공유 로컬 DB — badges 마스터는 불가침, 조회와 실패-롤백 INSERT 만 수행한다.
 */
@SpringBootTest
@Transactional
@DisplayName("V33 뱃지 그림 주소 — 전 행 채움·NOT NULL (실 PostGIS)")
class BadgeIconUrlSeedTest {

	@Autowired
	private EntityManager em;

	@Test
	void 모든_뱃지에_그림_주소가_채워져_있다() {
		// 검증: FR-BADGE-13 — 은퇴 3종 포함 26행 전부. NULL 이 하나라도 있으면 회색 칸이 새는 것
		Number nullCount = (Number) em.createNativeQuery(
				"SELECT count(*) FROM badges WHERE icon_url IS NULL")
			.getSingleResult();

		assertThat(nullCount.longValue()).isZero();
	}

	@Test
	void 그림_없는_뱃지는_시딩할_수_없다() {
		// 검증: FR-BADGE-13 — V33 의 NOT NULL 제약이 icon_url 누락 시딩을 DB 수준에서 거부한다
		assertThatThrownBy(() -> em.createNativeQuery("""
				INSERT INTO badges (code, name, condition_type, condition_value)
				VALUES ('NO_ICON_TEST', '그림 없는 뱃지', 'SPECIAL', '{"value": 1}')
				""")
			.executeUpdate())
			.isInstanceOf(PersistenceException.class);
	}
}
