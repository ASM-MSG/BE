package com.msg.fillmap.event.entity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 영상 댓글 (event_video_comments, MSG-441). 행사방의 유일한 대화 표면이라 표시 재료는 작성자·내용·
 * 작성 시각 셋뿐이고 수정 이력은 남기지 않는다 — 필요해지면 컬럼 하나를 뒤에 더하는 변경이다.
 * 삭제는 소프트가 아니라 실제 행 삭제다 (신고·블라인드 대상이 아니라 남겨 둘 이유가 없다).
 * <p>
 * userId 를 연관으로 매핑하지 않는 것은 이 엔티티를 축으로 {@code User} 를 타고 갈 일이 없어서다 —
 * 목록의 작성자 닉네임은 리포지토리가 세타 조인 + 생성자 프로젝션으로 한 번에 읽는다.
 */
@Entity
@Table(name = "event_video_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventVideoComment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "video_id", nullable = false)
	private EventVideo video;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "content", length = 500, nullable = false)
	private String content;

	/**
	 * 작성 시각. DDL 에 DEFAULT 가 있어도 JPA save 는 이 컬럼을 INSERT 목록에 포함하므로 값이 null 이면
	 * NOT NULL 위반이다 — 그래서 팩터리가 반드시 채운다. {@code @CreationTimestamp} 를 쓰지 않는 이유는
	 * 그것이 JVM 기본 존(로컬 KST)의 벽시계를 만들어 9시간 앞선 값을 저장하기 때문이다(Report 선례).
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private EventVideoComment(EventVideo video, Long userId, String content, LocalDateTime now) {
		this.video = video;
		this.userId = userId;
		this.content = content;
		// DB TIMESTAMP 정밀도(µs)로 절단 — 리눅스 나노초 시계에서 재조회 값과 응답 값이 어긋나지 않게
		this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
	}

	/**
	 * 댓글 작성. now 는 서비스가 한 요청에 한 번 고정한 시각이다 — 엔티티가 스스로 시계를 읽으면 잠금 가드
	 * 판정 시각과 저장 시각이 한 요청 안에서 갈린다 (MSG-441 §확정 사항).
	 */
	public static EventVideoComment create(EventVideo video, long userId, String content, LocalDateTime now) {
		return new EventVideoComment(video, userId, content, now);
	}

	/** 내용 수정 — 더티 체킹으로 나간다. 수정 시각을 남기지 않는 것은 표시 재료가 아니기 때문이다. */
	public void updateContent(String content) {
		this.content = content;
	}
}
