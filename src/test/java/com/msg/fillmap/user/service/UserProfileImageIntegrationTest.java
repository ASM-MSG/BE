package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.service.FriendService;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 프로필 이미지 저장·노출 (MSG-373 FR-2·3·4·6, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에
 * 시드를 남기지 않는다 (UserProfileIntegrationTest 패턴). 검증 축은 "컬럼에 뭐가 저장되고 어느 응답에
 * 실리는가" — S3 정리(afterCommit)는 커밋이 없어 여기서 관찰할 수 없어 UserProfileImageS3Test 가 맡는다.
 */
@SpringBootTest
@Transactional
@DisplayName("프로필 이미지 저장·노출 (실 DB)")
class UserProfileImageIntegrationTest {

	@Autowired
	private UserService userService;

	@Autowired
	private FriendService friendService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	/** 실 S3 호출 차단 — 확정 경로가 HeadObject·CopyObject 를 부른다. 정상 응답으로 스텁해 DB 축만 본다. */
	@MockitoBean
	private S3Client s3Client;

	private User me;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(1048576L).build());
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		me = seedUser("채우미");
	}

	private User seedUser(String nickname) {
		return userRepository.save(
			User.createLocalUser("profile-image-" + UUID.randomUUID() + "@example.com", "hash", nickname));
	}

	private String pendingKey(Long userId) {
		return "profiles/pending/%d/%s.jpg".formatted(userId, UUID.randomUUID());
	}

	/** 1차 캐시 재반환이면 더티 체킹 UPDATE 미실행도 통과해 버린다 — DB 재조회를 강제한다. */
	private String reloadProfileImageUrl(Long userId) {
		em.flush();
		em.clear();
		return userRepository.findById(userId).orElseThrow().getProfileImageUrl();
	}

	@Nested
	@DisplayName("변경 확정과 제거")
	class 변경_확정과_제거 {

		@Test
		@DisplayName("확정하면 공개 URL 이 저장되고 갱신된 프로필이 반환된다 (FR-1·§D-1)")
		void 확정하면_공개_URL이_저장되고_갱신된_프로필이_반환된다() {
			UserProfileResponseDto updated = userService.updateProfileImage(me.getId(), pendingKey(me.getId()));

			assertThat(updated.profileImageUrl())
				.startsWith("https://")
				.contains("/profiles/original/" + me.getId() + "/")
				.endsWith(".jpg");
			assertThat(updated.nickname()).isEqualTo("채우미");
			assertThat(reloadProfileImageUrl(me.getId())).isEqualTo(updated.profileImageUrl());
		}

		@Test
		@DisplayName("제거하면 기본 상태(null)로 돌아간다 (FR-6)")
		void 제거하면_기본_상태로_돌아간다() {
			userService.updateProfileImage(me.getId(), pendingKey(me.getId()));

			assertThat(userService.removeProfileImage(me.getId()).profileImageUrl()).isNull();
			assertThat(reloadProfileImageUrl(me.getId())).isNull();
		}

		@Test
		@DisplayName("이미 기본 상태여도 제거는 성공한다 (멱등)")
		void 이미_기본_상태여도_제거는_성공한다() {
			assertThat(userService.removeProfileImage(me.getId()).profileImageUrl()).isNull();
		}
	}

	@Nested
	@DisplayName("조회 반영")
	class 조회_반영 {

		@Test
		@DisplayName("내 프로필 조회에 이미지 URL 과 가입 시각이 담긴다 (FR-2·3)")
		void 내_프로필_조회에_이미지_URL과_가입_시각이_담긴다() {
			String url = userService.updateProfileImage(me.getId(), pendingKey(me.getId())).profileImageUrl();
			em.flush();
			em.clear();

			UserProfileResponseDto profile = userService.getMyProfile(me.getId());

			assertThat(profile.profileImageUrl()).isEqualTo(url);
			assertThat(profile.createdAt()).isEqualTo(me.getCreatedAt());
		}

		@Test
		@DisplayName("미설정이면 이미지 URL 은 null 이고 가입 시각은 그대로 담긴다 (FR-2)")
		void 미설정이면_이미지_URL은_null이다() {
			UserProfileResponseDto profile = userService.getMyProfile(me.getId());

			assertThat(profile.profileImageUrl()).isNull();
			assertThat(profile.createdAt()).isNotNull();
		}

		/**
		 * 친구 축 3종은 코드 변경 없이 반영된다는 전제(PRD 7절)의 회귀 방지 1건. 프로젝션이 컬럼 원문을
		 * 그대로 싣기 때문에 저장 값이 완성 URL 이어야 성립한다 (§D-1). 3종 전수 확인은 불요.
		 */
		@Test
		@DisplayName("설정한 이미지가 친구 목록 응답에 반영된다 (FR-4)")
		void 설정한_이미지가_친구_목록_응답에_반영된다() {
			User friend = seedUser("친구");
			friendService.request(friend.getId(), me.getFriendCode());
			friendService.accept(me.getId(), friend.getId());
			String url = userService.updateProfileImage(friend.getId(), pendingKey(friend.getId())).profileImageUrl();
			em.flush();
			em.clear();

			List<FriendListItemResponseDto> friends = friendService.getFriends(me.getId(), null);

			assertThat(friends).singleElement()
				.extracting(FriendListItemResponseDto::profileImageUrl)
				.isEqualTo(url);
		}
	}
}
