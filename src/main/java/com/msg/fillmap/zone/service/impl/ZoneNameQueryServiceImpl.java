package com.msg.fillmap.zone.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.zone.repository.ZoneRepository;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 격자 표시명 계산 구현 (MSG-341). geospatial 0 — zones 정수 컬럼 풀스캔 1회로 스냅샷을 만들고
 * 이름 산술은 리졸버가 메모리에서 한다.
 * 프로세스 내 캐시를 두지 않는다 (D-2): zones 는 48행이라 풀스캔이 밀리초 미만이고, 재기동 없이 일어나는
 * zone 수동 삭제(MSG-259 D-8 의 공식 제거 절차)도 다음 요청부터 즉시 반영돼야 하기 때문이다(FR-6).
 * 트랜잭션 없는 소비처(hotzone·search)도 있어 자체 readOnly 트랜잭션을 건다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoneNameQueryServiceImpl implements ZoneNameQueryService {

	private final ZoneRepository zoneRepository;

	@Override
	public ZoneNameResolver resolver() {
		return new ZoneNameResolver(zoneRepository.findAll());
	}
}
