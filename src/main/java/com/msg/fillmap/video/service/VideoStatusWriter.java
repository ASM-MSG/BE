package com.msg.fillmap.video.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 인코딩 상태 전이를 각각 독립 트랜잭션으로 커밋한다.
 *
 * 별도 빈으로 둔 이유: @Transactional 은 프록시로 동작하므로 같은 클래스 안에서 호출하면(self-invocation)
 * 프록시를 거치지 않아 REQUIRES_NEW 가 무시된다. 그러면 ENCODING 이 DB 에 보이지 않고, 실패 시
 * markFailed 까지 함께 롤백돼 UPLOADED 로 남는다.
 */
@Component
@RequiredArgsConstructor
public class VideoStatusWriter {

	private final VideoRepository videoRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markEncoding(Long videoId) {
		videoRepository.findById(videoId).ifPresent(Video::markEncoding);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markReady(Long videoId, String encodedKey, String thumbnailKey) {
		videoRepository.findById(videoId).ifPresent(video -> video.markReady(encodedKey, thumbnailKey));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long videoId) {
		videoRepository.findById(videoId).ifPresent(Video::markFailed);
	}
}
