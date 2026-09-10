package com.msg.fillmap.video.repository;

/**
 * 전역 목록 페이지의 작성자 닉네임 배치 조회 결과 (MSG-371). userId → nickname 매핑을 만들 재료만 담는다 —
 * 항목마다 작성자를 다시 읽지 않기 위해(N+1 금지) 페이지의 작성자 id 를 모아 IN 1회로 받는다.
 * JPQL 별칭(u.id AS userId, u.nickname AS nickname)을 getter 프로퍼티명에 맞춘다(ExploreGridProjection 선례).
 */
public interface AuthorNicknameProjection {

	Long getUserId();

	String getNickname();
}
