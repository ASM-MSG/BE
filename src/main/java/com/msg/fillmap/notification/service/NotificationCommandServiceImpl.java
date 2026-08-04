package com.msg.fillmap.notification.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.repository.NotificationRepository;

/**
 * outbox 기록 구현 (MSG-179 D6). @Transactional 전파 REQUIRED — 호출자 트랜잭션 참여로
 * 비즈니스 커밋과 원자적(FR-3), 없으면 자체 트랜잭션. afterCommit 훅·즉시 발행 없음 —
 * 커밋 후 전달은 릴레이 폴링(5s) 몫이다.
 */
@Service
@RequiredArgsConstructor
public class NotificationCommandServiceImpl implements NotificationCommandService {

	private final NotificationRepository notificationRepository;

	@Override
	@Transactional
	public void record(Long userId, NotificationCategory category, String eventKey, String title, String body) {
		notificationRepository.insert(userId, category.name(), eventKey, title, body);
	}
}
