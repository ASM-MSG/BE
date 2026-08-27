package com.msg.fillmap.video.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.CloudFrontProperties;

class ThumbnailUrlPresignerTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("mediaKey가 null이면 null을 반환한다")
	void nullKeyReturnsNull() {
		ThumbnailUrlPresigner presigner = new ThumbnailUrlPresigner(
			mock(S3Presigner.class), mock(AwsProperties.class)
		);

		assertThat(presigner.presign(null)).isNull();
	}

	@Test
	@DisplayName("CloudFront가 꺼지면 S3 사전서명 URL을 반환한다")
	void cloudFrontDisabledFallsBackToS3() {
		AwsProperties awsProperties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)
		);
		CloudFrontProperties cloudFrontProperties = new CloudFrontProperties(false, null, null, null);
		try (S3Presigner s3Presigner = S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
			.build()) {
			ThumbnailUrlPresigner presigner = new ThumbnailUrlPresigner(
				s3Presigner, awsProperties, cloudFrontProperties
			);

			String url = presigner.presign("videos/encoded/1/2.mp4");

			assertThat(url).startsWith(
				"https://fillmap-video-dev.s3.ap-northeast-2.amazonaws.com/videos/encoded/1/2.mp4?"
			);
			assertThat(url).contains("X-Amz-Expires=600");
		}
	}

	@Test
	@DisplayName("CloudFront가 켜지면 배포 도메인과 canned policy 서명으로 URL을 발급한다")
	void cloudFrontSignedUrl() throws Exception {
		Path privateKey = writePrivateKey();
		CloudFrontProperties properties = new CloudFrontProperties(
			true, "d111111abcdef8.cloudfront.net", "K123456789", privateKey
		);
		ThumbnailUrlPresigner presigner = new ThumbnailUrlPresigner(
			mock(S3Presigner.class), mock(AwsProperties.class), properties
		);

		String url = presigner.presign("videos/encoded/1/2.mp4");

		assertThat(url).startsWith("https://d111111abcdef8.cloudfront.net/videos/encoded/1/2.mp4?");
		assertThat(url).contains("Expires=", "Signature=", "Key-Pair-Id=K123456789");
	}

	@Test
	@DisplayName("CloudFront가 켜졌는데 필수 설정이 빠지면 기동 시점에 실패한다")
	void invalidCloudFrontConfiguration() {
		CloudFrontProperties properties = new CloudFrontProperties(true, "", "", null);

		assertThatThrownBy(() -> new ThumbnailUrlPresigner(
			mock(S3Presigner.class), mock(AwsProperties.class), properties
		)).isInstanceOf(IllegalStateException.class);
	}

	private Path writePrivateKey() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keyPair = generator.generateKeyPair();
		String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
			.encodeToString(keyPair.getPrivate().getEncoded());
		Path path = tempDir.resolve("private-key.pem");
		Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
		return path;
	}
}
