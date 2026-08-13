package com.msg.fillmap.notification.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.dto.NotificationPreferenceResponseDto;
import com.msg.fillmap.notification.dto.NotificationPreferenceResponseDto.CategoryPreferenceDto;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.entity.NotificationOptOutId;
import com.msg.fillmap.notification.exception.NotificationErrorCode;
import com.msg.fillmap.notification.repository.NotificationOptOutRepository;

/**
 * 알림 수신 설정 구현 (MSG-180 D5) — 179 의 전부-허용 스텁을 실제 설정 조회로 교체.
 * isEnabled 는 PK 단건 exists 1문장이라 캐시 없음(발송량이 사용자·일 상한으로 눌려 있다 — D5 실측).
 * 컨슈머의 isEnabled 호출은 비트랜잭션 컨텍스트의 자동커밋 단문 SELECT — @Transactional 은
 * modifying native 요건인 update 에만 붙는다 (PushTokenServiceImpl 선례).
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

	private final NotificationOptOutRepository notificationOptOutRepository;

	@Override
	public boolean isEnabled(Long userId, NotificationCategory category) {
		return !notificationOptOutRepository.existsById(new NotificationOptOutId(userId, category));
	}

	@Override
	public NotificationPreferenceResponseDto getPreferences(Long userId) {
		return currentPreferences(userId);
	}

	@Override
	@Transactional
	public NotificationPreferenceResponseDto update(Long userId, String category, boolean enabled) {
		NotificationCategory parsed = parseCategory(category);
		if (enabled) {
			notificationOptOutRepository.deleteOptOut(userId, parsed.name());
		} else {
			notificationOptOutRepository.insertOptOut(userId, parsed.name());
		}
		return currentPreferences(userId);
	}

	/** off 행 조회 후 전 카테고리 합성 — 행 부재 카테고리는 on, 순서는 NotificationCategory enum 선언 순 고정. */
	private NotificationPreferenceResponseDto currentPreferences(Long userId) {
		Set<NotificationCategory> optOuts = notificationOptOutRepository.findAllByIdUserId(userId).stream()
			.map(optOut -> optOut.getId().getCategory())
			.collect(Collectors.toSet());
		List<CategoryPreferenceDto> preferences = Stream.of(NotificationCategory.values())
			.map(category -> new CategoryPreferenceDto(category, !optOuts.contains(category)))
			.toList();
		return new NotificationPreferenceResponseDto(preferences);
	}

	private NotificationCategory parseCategory(String category) {
		try {
			// Locale.ROOT: 터키어 로케일 JVM 의 배포 로케일 의존 차단 (PushTokenServiceImpl.parsePlatform 선례)
			return NotificationCategory.valueOf(category.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(NotificationErrorCode.INVALID_CATEGORY);
		}
	}
}
