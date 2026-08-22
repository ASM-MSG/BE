package com.msg.fillmap.event.repository;

import java.time.LocalDateTime;

/**
 * 댓글 목록 한 줄 (MSG-441 API 6). 작성자 닉네임을 세타 조인으로 함께 읽는 생성자 프로젝션이라 항목마다
 * users 를 다시 읽지 않는다(N+1 방지). 매 요청 users 를 읽는 구조라 닉네임 변경이 다음 조회에 그대로
 * 반영된다 — 비정규화 사본을 두지 않는 MSG-371 선례와 같다.
 */
public record EventVideoCommentRow(Long commentId, Long authorId, String authorNickname, String content,
	LocalDateTime createdAt) {
}
