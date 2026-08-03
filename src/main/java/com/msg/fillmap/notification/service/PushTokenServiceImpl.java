package com.msg.fillmap.notification.service;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.dto.PushTokenRequestDto;
import com.msg.fillmap.notification.entity.PushPlatform;
import com.msg.fillmap.notification.exception.NotificationErrorCode;
import com.msg.fillmap.notification.repository.PushTokenRepository;

/**
 * 푸시 토큰 등록/해제 구현 (MSG-178). @Transactional 은 modifying native query 요건.
 * 능동 만료 없음 — 무효 토큰 정리는 FCM 응답 기반 MSG-179 FR-5 소관, last_used_at 은 스테일 판단 재료.
 */
@Service
@RequiredArgsConstructor
public class PushTokenServiceImpl implements PushTokenService {

	private final PushTokenRepository pushTokenRepository;

	@Override
	@Transactional
	public void register(long userId, PushTokenRequestDto request) {
		PushPlatform platform = parsePlatform(request.platform());
		pushTokenRepository.upsert(request.fcmToken(), userId, platform.name(), request.appVersion());
	}

	@Override
	@Transactional
	public void unregister(long userId, String fcmToken) {
		pushTokenRepository.deleteByTokenAndUser(fcmToken, userId);
	}

	private PushPlatform parsePlatform(String platform) {
		try {
			// Locale.ROOT: 터키어 로케일 JVM에서 "ios"→"İOS"가 되는 배포 로케일 의존 차단 (Codex 1R P2)
			return PushPlatform.valueOf(platform.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(NotificationErrorCode.INVALID_PLATFORM);
		}
	}
}
