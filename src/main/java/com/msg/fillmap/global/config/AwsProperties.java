package com.msg.fillmap.global.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
	@NotBlank String region,
	@Valid @NotNull S3 s3
) {

	/**
	 * 버킷 상대 키 → 공개 주소 (MSG-394 D3). 미션 시더 셋이 산출물의 키를 자기 환경 버킷·리전으로
	 * 조립한다 — 앱이 주소가 아니라 키를 입력으로 받으므로 외부 도메인이 image_url 에 저장되는 상태 자체가
	 * 표현 불가능하고, 같은 스냅샷 파일을 버킷이 서로 다른 dev·prod 양쪽에 그대로 쓸 수 있다.
	 * 버킷과 리전을 가진 타입이 자기 자신이라 새 빈도 새 클래스도 필요 없다.
	 *
	 * 키가 없으면 주소도 없다(null) — 대표 이미지 결측이 세 시더 모두의 정상 경로라, 호출부마다 같은
	 * null 가드를 세 번 두지 않는다.
	 */
	public String publicUrl(String key) {
		if (key == null) {
			return null;
		}
		return "https://%s.s3.%s.amazonaws.com/%s".formatted(s3().bucket(), region(), key);
	}

	/**
	 * bucket 에 S3 명명 규칙을 강제한다. @ConfigurationProperties 바인딩은 @Value 와 달리 해석하지 못한
	 * 플레이스홀더를 예외 없이 리터럴로 통과시키므로(PropertySourcesPlaceholdersResolver), 환경변수가
	 * 빠지면 버킷명이 "${S3_BUCKET_VIDEO}" 가 된 채 기동해버린다. 이 패턴이 그 경우를 기동 시점에 잡는다.
	 */
	public record S3(
		@NotBlank
		@Pattern(regexp = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", message = "유효한 S3 버킷 이름이 아닙니다")
		String bucket,
		@Positive long maxUploadBytes,
		// 선분석(purpose=HIGHLIGHT_PREVIEW) 원본 전용 상한 — 3분 4K 60fps 최악 약 2GB 커버 (MSG-351 D-4)
		@Positive long maxHighlightUploadBytes
	) {
	}
}
