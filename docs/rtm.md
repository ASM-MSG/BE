# 요구사항 추적성 매트릭스 (RTM)

`scripts/generate-rtm.sh` 가 생성한다. 손으로 고치지 말 것. 원천은 테스트의 `// 검증: FR-...` 주석과 `docs/srs.md` 다.

**병합 충돌이 나면 손으로 합치지 말고 재생성한다.** 어느 쪽이든 골라 충돌만 없앤 뒤(`git checkout --ours docs/rtm.md` 등)
스크립트를 다시 돌려 그 결과를 커밋한다. 이 표는 두 원천에서 계산되는 값이라 양쪽 diff를 섞으면 어느 쪽과도 다른 상태가 된다.

요약: FR 250건 중 테스트 연결 219건, 검증 공백 0건 (계획·폐기라 테스트 부재가 정상인 24건, 성격상 테스트 비대상 7건 별도)

| 요구사항 ID | SRS 상태 | 검증 테스트 |
|---|---|---|
| FR-MAP-01 | 계획 | (없음) |
| FR-MAP-02 | 계획 | (없음) |
| FR-MAP-03 | 계획 | (없음) |
| FR-MAP-04 | 계획 | (없음) |
| FR-MAP-05 | 폐기됨 | (없음) |
| FR-MAP-06 | 계획 | (없음) |
| FR-MAP-08 | 계획 | (없음) |
| FR-MAP-09 | 구현됨 | GridHourlyUploadControllerTest, VideoHourlyUploadQueryTest, VideoHourlyUploadServiceTest |
| FR-MAP-07 | 계획 | (없음) |
| FR-MAP-10 | 구현됨 | CollectionControllerTest, CollectionGridsFilterRepositoryTest, UserGridQueryServiceImplTest |
| FR-GRID-01 | 구현됨 | GridEncoderTest |
| FR-GRID-02 | 구현됨 | GridEncoderTest |
| FR-GRID-03 | 구현됨 | GridEncoderTest |
| FR-GRID-04 | 구현됨 | VideoServiceIntegrationTest |
| FR-GRID-05 | 구현됨 | GridEncoderTest, GridSampleFixtureTest |
| FR-GRID-06 | 구현됨 | GridControllerTest, GridQueryServiceIntegrationTest, GridRepositoryTest |
| FR-GRID-07 | 구현됨 | GridQueryServiceIntegrationTest, GridRepositoryTest |
| FR-GRID-08 | 구현됨 | GridControllerTest, GridCursorTest, GridQueryServiceIntegrationTest, GridRepositoryTest |
| FR-GRID-09 | 구현됨 | GridControllerTest, GridCursorTest, GridQueryServiceIntegrationTest |
| FR-GRID-10 | 구현됨 | GridRegionCodeBackfillTest |
| FR-GRID-11 | 구현됨 | GridEpsg5179MigrationTest |
| FR-GRID-12 | 구현됨 | (없음) |
| FR-GRID-13 | 구현됨 | FriendControllerTest, GridAggregationIntegrationTest, GridControllerTest |
| FR-ZONE-01 | 구현됨 | ZoneRepositoryTest |
| FR-ZONE-02 | 구현됨 | ZoneRepositoryTest |
| FR-ZONE-03 | 구현됨 | GridQueryServiceIntegrationTest, ZoneNamingContractTest |
| FR-ZONE-04 | 구현됨 | GridQueryServiceIntegrationTest, ZoneNamingContractTest |
| FR-ZONE-05 | 구현됨 | GridControllerTest, GridQueryServiceIntegrationTest, HotZoneControllerTest, HotZoneServiceImplTest, PlaceSearchControllerTest, PlaceSearchServiceTest |
| FR-ZONE-06 | 구현됨 | GridQueryServiceIntegrationTest |
| FR-ZONE-07 | 구현됨 | ZoneNamingContractTest |
| FR-ZONE-08 | 구현됨 | ZoneNameQueryServiceIntegrationTest |
| FR-ZONE-09 | 구현됨 | GridQueryServiceIntegrationTest |
| FR-ZONE-10 | 구현됨 | ZoneNamingContractTest |
| FR-ZONE-11 | 구현됨 | ZoneControllerTest, ZoneQueryServiceTest |
| FR-ZONE-12 | 구현됨 | ZoneRepositoryTest, ZoneSeederTest |
| FR-ZONE-13 | 구현됨 | ZoneNamingContractTest |
| FR-ZONE-14 | 구현됨 | (없음) |
| FR-REGION-01 | 구현됨 | RegionGeoJsonReaderTest, RegionGridCountTest, RegionRepositoryTest, RegionSeederTest |
| FR-REGION-02 | 구현됨 | RegionControllerTest, RegionQueryServiceImplTest, RegionReverseGeocodeTest |
| FR-REGION-03 | 구현됨 | GridRegionCodeBackfillTest, RegionStatsPointGridQueryServiceTest, RegionStatsRecomputeTest |
| FR-REGION-04 | 구현됨 | RegionStatsPointGridQueryServiceTest, RegionStatsQueryTest, RegionStatsRecomputeTest |
| FR-REGION-05 | 구현됨 | RegionStatsCommandServiceTest, RegionStatsConcurrencyTest, RegionStatsRecomputeTest |
| FR-REGION-06 | 구현됨 | RegionControllerTest, RegionStatsQueryServiceTest, RegionStatsQueryTest |
| FR-REGION-07 | 구현됨 | RegionControllerTest, RegionStatsPointGridQueryServiceTest |
| FR-REGION-08 | 구현됨 | GridControllerTest, GridQueryServiceIntegrationTest, GridRepositoryTest, HotZoneControllerTest, HotZoneServiceImplTest |
| FR-REGION-09 | 구현됨 | GridQueryServiceIntegrationTest |
| FR-REGION-10 | 구현됨 | GridQueryServiceIntegrationTest, GridRegionCodeBackfillTest, GridRepositoryTest, HotZoneServiceImplTest |
| FR-REGION-11 | 구현됨 | GridQueryServiceIntegrationTest |
| FR-REGION-12 | 구현됨 | GridQueryServiceIntegrationTest, GridRepositoryTest, RegionStatsQueryTest, RegionStatsRecomputeTest |
| FR-REGION-13 | 계획 | (없음) |
| FR-REGION-14 | 구현됨 | RegionControllerTest, RegionNationalStatQueryTest, RegionStatsQueryServiceTest |
| FR-REGION-15 | 구현됨 | RegionControllerTest, RegionDistrictQueryTest, RegionDistrictServiceTest |
| FR-SEARCH-01 | 구현됨 | KakaoLocalClientTest, PlaceSearchControllerTest, PlaceSearchServiceTest |
| FR-SEARCH-02 | 구현됨 | KakaoLocalClientTest, PlaceSearchAggregationIntegrationTest, PlaceSearchControllerTest, PlaceSearchServiceTest |
| FR-SEARCH-03 | 구현됨 | KakaoLocalClientTest |
| FR-SEARCH-04 | 구현됨 | KakaoLocalClientTest, PlaceSearchControllerTest |
| FR-SEARCH-05 | 구현됨 | PlaceSearchAggregationIntegrationTest, PlaceSearchServiceTest, SearchKeywordCommandServiceImplTest, SearchKeywordCommandTransactionTest, SearchKeywordDailyCountRepositoryTest |
| FR-SEARCH-06 | 구현됨 | PlaceSearchServiceTest, SearchKeywordCommandServiceImplTest |
| FR-SEARCH-07 | 구현됨 | TrendingKeywordControllerTest, TrendingKeywordQueryServiceImplTest |
| FR-SEARCH-08 | 구현됨 | TrendingKeywordControllerTest |
| FR-SEARCH-09 | 구현됨 | TrendingKeywordControllerTest, TrendingKeywordQueryServiceImplTest |
| FR-SEARCH-10 | 구현됨 | SearchKeywordCommandServiceImplTest |
| FR-SEARCH-11 | 구현됨 | SearchKeywordDailyCountRepositoryTest |
| FR-SEARCH-12 | 구현됨 | SearchKeywordDailyCountRepositoryTest |
| FR-SEARCH-13 | 계획 | (없음) |
| FR-SEARCH-14 | 계획 | (없음) |
| FR-SEARCH-15 | 구현됨 | RegionExploreControllerTest, RegionExploreCursorTest, RegionExploreQueryTest, RegionExploreServiceTest |
| FR-HOTZONE-01 | 구현됨 | (없음) |
| FR-HOTZONE-02 | 구현됨 | HotScoreCommandServiceImplTest |
| FR-HOTZONE-03 | 구현됨 | HotScoreCommandServiceImplTest |
| FR-HOTZONE-04 | 구현됨 | HotScoreCommandServiceImplTest, HotZoneServiceImplTest |
| FR-HOTZONE-05 | 구현됨 | HotScoreCommandServiceImplTest |
| FR-HOTZONE-06 | 구현됨 | HotZoneServiceImplTest |
| FR-HOTZONE-07 | 구현됨 | HotZoneServiceImplTest, MissionPublicAccessHttpTest |
| FR-HOTZONE-08 | 구현됨 | HotZoneServiceImplTest |
| FR-HOTZONE-09 | 구현됨 | HotZoneControllerTest, HotZoneServiceImplTest |
| FR-HOTZONE-10 | 구현됨 | HotZoneControllerTest |
| FR-HOTZONE-11 | 구현됨 | HotScoreCommandServiceImplTest |
| FR-HOTZONE-12 | 구현됨 | (없음) |
| FR-VIDEO-01 | 구현됨 | VideoControllerTest |
| FR-VIDEO-02 | 구현됨 | VideoPresignTest |
| FR-VIDEO-03 | 구현됨 | VideoPresignTest |
| FR-VIDEO-04 | 구현됨 | VideoPresignTest, VideoS3KeyValidationTest |
| FR-VIDEO-05 | 구현됨 | VideoServiceIntegrationTest |
| FR-VIDEO-06 | 구현됨 | (없음) |
| FR-VIDEO-07 | 구현됨 | VideoRecordedAtValidationTest |
| FR-VIDEO-08 | 구현됨 | VideoReplaceIntegrationTest, VideoS3KeyValidationTest |
| FR-VIDEO-09 | 구현됨 | VideoReplaceConcurrencyTest, VideoUploadRollbackCompensationTest |
| FR-VIDEO-10 | 구현됨 | VideoReplaceIntegrationTest |
| FR-VIDEO-11 | 구현됨 | VideoDeleteIntegrationTest, VideoS3CleanupTest |
| FR-VIDEO-12 | 구현됨 | VideoPlaybackControllerTest, VideoPlaybackServiceTest |
| FR-VIDEO-13 | 구현됨 | VideoBlindIntegrationTest, VideoPlaybackServiceTest |
| FR-VIDEO-14 | 구현됨 | VideoGlobalListViewCountIntegrationTest, VideoPlaybackControllerTest, VideoPlaybackServiceTest, VideoPlaybackViewCountIntegrationTest, VideoViewCountQueryTest |
| FR-VIDEO-15 | 구현됨 | VideoServiceIntegrationTest, VideoVisibilityControllerTest, VideoVisibilityIntegrationTest |
| FR-VIDEO-16 | 구현됨 | VideoPlaybackServiceTest |
| FR-VIDEO-17 | 구현됨 | RegionExploreQueryTest, VideoGlobalCoverQueryTest, VideoGlobalListQueryTest, VideoServiceIntegrationTest, VideoVisibilityIntegrationTest |
| FR-VIDEO-18 | 구현됨 | VideoAuthorNicknameIntegrationTest, VideoGlobalCoverServiceTest, VideoPlaybackServiceTest |
| FR-MEDIA-01 | 구현됨 | FfmpegRunnerTest, VideoEncodingServiceTest |
| FR-MEDIA-02 | 구현됨 | AiBlurPollerTest, VideoBlurTransitionTest, VideoEncodingAiTriggerTest, VideoEncodingServiceTest, VideoEncodingTriggerTest, VideoGridQueryServiceTest, VideoPlaybackServiceTest, VideoStatusTransitionTest, VideoStatusWriterTest |
| FR-MEDIA-03 | 구현됨 | VideoEncodingServiceTest |
| FR-MEDIA-04 | 구현됨 | AiBlurPollerTest, VideoBlurTransitionTest, VideoEncodingServiceTest, VideoPlaybackServiceTest |
| FR-MEDIA-05 | 구현됨 | AiEnabledContextTest, VideoEncodingAiTriggerTest |
| FR-MEDIA-17 | 구현됨 | (없음) |
| FR-MEDIA-06 | 구현됨 | AiBlurPollerTest |
| FR-MEDIA-07 | 구현됨 | AiBlurPollerTest, VideoStatusWriterTest |
| FR-MEDIA-08 | 구현됨 | VideoNotificationIntegrationTest |
| FR-MEDIA-09 | 구현됨 | VideoBlurResultTest |
| FR-MEDIA-10 | 구현됨 | VideoPlaybackControllerTest, VideoPlaybackServiceTest |
| FR-MEDIA-11 | 구현됨 | HighlightPreviewControllerTest, HighlightPreviewServiceTest |
| FR-MEDIA-12 | 구현됨 | HighlightPreviewControllerTest, HighlightPreviewServiceTest |
| FR-MEDIA-13 | 구현됨 | HighlightPreviewServiceTest |
| FR-MEDIA-14 | 구현됨 | HighlightPreviewServiceTest, VideoPresignTest, VideoS3KeyValidationTest |
| FR-MEDIA-15 | 구현됨 | HighlightPreviewServiceTest |
| FR-MEDIA-16 | 구현됨 | (없음) |
| FR-COLLECT-01 | 구현됨 | BadgeAwardServiceIntegrationTest |
| FR-COLLECT-02 | 구현됨 | BadgeAwardServiceIntegrationTest |
| FR-COLLECT-03 | 구현됨 | VideoReplaceIntegrationTest |
| FR-COLLECT-04 | 구현됨 | VideoDeleteIntegrationTest |
| FR-COLLECT-05 | 구현됨 | VideoDeleteIntegrationTest |
| FR-COLLECT-06 | 구현됨 | VideoDeleteConcurrencyTest |
| FR-COLLECT-07 | 구현됨 | CollectionControllerTest, UserGridQueryServiceImplTest, UserGridRepositoryTest |
| FR-COLLECT-08 | 구현됨 | CollectionControllerTest, CollectionGridsFilterRepositoryTest, CollectionGridsRepositoryTest, UserGridQueryServiceImplTest |
| FR-COLLECT-09 | 구현됨 | CollectionControllerTest, CollectionGridsRegionNameTest |
| FR-COLLECT-10 | 구현됨 | CollectionControllerTest, RegionVideosRepositoryTest, UserGridQueryServiceImplTest |
| FR-COLLECT-11 | 구현됨 | GridVideoControllerTest, VideoGridQueryServiceTest, VideoGridQueryTest |
| FR-COLLECT-12 | 구현됨 | CollectionGridsRepositoryTest, RegionVideosRepositoryTest, UserGridRepositoryTest |
| FR-BADGE-01 | 구현됨 | BadgeQueryServiceIntegrationTest, BadgeSchemaSeedTest, MissionTypeBadgeSeedTest |
| FR-BADGE-02 | 구현됨 | BadgeAwardServiceIntegrationTest, BadgeAwardServiceTest |
| FR-BADGE-03 | 구현됨 | BadgeAwardServiceIntegrationTest, BadgeAwardServiceTest, StreakCommandServiceIntegrationTest |
| FR-BADGE-04 | 구현됨 | BadgeAwardServiceIntegrationTest, StreakCommandServiceIntegrationTest |
| FR-BADGE-05 | 구현됨 | BadgeAwardServiceIntegrationTest |
| FR-BADGE-06 | 구현됨 | BadgeSchemaSeedTest, MissionTypeBadgeSeedTest |
| FR-BADGE-07 | 구현됨 | BadgeControllerTest, BadgeQueryServiceIntegrationTest |
| FR-BADGE-08 | 구현됨 | BadgeAwardServiceIntegrationTest, BadgeQueryServiceIntegrationTest, StreakCommandServiceIntegrationTest |
| FR-BADGE-09 | 구현됨 | BadgeControllerTest, BadgeFeaturedServiceIntegrationTest, BadgeSchemaSeedTest |
| FR-BADGE-10 | 구현됨 | BadgeNearMissConcurrencyTest, BadgeNearMissIntegrationTest |
| FR-BADGE-11 | 계획 | (없음) |
| FR-BADGE-12 | 구현됨 | BadgeNearMissIntegrationTest, MissionAwardServiceTest, MissionTypeBadgeSeedTest, UserMissionRepositoryTest |
| FR-BADGE-13 | 진행 중 | BadgeIconUrlSeedTest |
| FR-STREAK-01 | 구현됨 | VideoStreakIntegrationTest |
| FR-STREAK-02 | 구현됨 | StreakCommandServiceIntegrationTest |
| FR-STREAK-03 | 구현됨 | StreakCommandServiceIntegrationTest |
| FR-STREAK-04 | 구현됨 | StreakCommandServiceIntegrationTest |
| FR-STREAK-05 | 구현됨 | VideoStreakIntegrationTest |
| FR-STREAK-06 | 구현됨 | StreakCommandServiceIntegrationTest |
| FR-STREAK-07 | 구현됨 | StreakRemindSchedulerTest |
| FR-STREAK-08 | 구현됨 | CollectionControllerTest, UploadHistoryIntegrationTest, UserGridQueryServiceImplTest, UserGridRepositoryTest |
| FR-MISSION-01 | 구현됨 | CourseSeedContractTest, MissionControllerTest, MissionPublicAccessHttpTest, MissionQueryServiceImplTest, MissionSchemaMigrationTest |
| FR-MISSION-02 | 구현됨 | MissionAggregationHttpTest, MissionControllerTest, MissionQueryServiceClockTest, MissionRepositoryTest, MissionValidationHttpTest, MissionViewportFilterTest |
| FR-MISSION-03 | 구현됨 | CourseSeedContractTest, MissionAwardQueryTest, MissionAwardServiceTest |
| FR-MISSION-04 | 구현됨 | FestivalMissionSeederIntegrationTest, MissionAwardQueryTest, MissionAwardServiceTest, MissionProgressQueryTest, MissionRepositoryTest, MissionSchemaMigrationTest, PopupMissionSeederIntegrationTest, UserMissionRepositoryTest |
| FR-MISSION-05 | 구현됨 | VideoMissionIntegrationTest |
| FR-MISSION-06 | 구현됨 | MissionAwardServiceTest |
| FR-MISSION-07 | 폐기됨 | (없음) |
| FR-MISSION-08 | 진행 중 | CourseMissionSeederIntegrationTest, CourseSeedReaderTest, FestivalJsonlReaderTest, FestivalMissionSeederIntegrationTest, FestivalMissionSeederTest, PopupJsonlReaderTest, PopupMissionSeederIntegrationTest |
| FR-MISSION-09 | 구현됨 | CourseMissionSeederIntegrationTest, CourseSeedContractTest, CourseSeedReaderTest, MissionSchemaMigrationTest |
| FR-MISSION-10 | 구현됨 | MissionRepositoryTest, PopupJsonlReaderTest, PopupMissionSeederIntegrationTest |
| FR-MISSION-11 | 구현됨 | CourseMissionSeederIntegrationTest, FestivalMissionSeederIntegrationTest, PopupMissionSeederIntegrationTest |
| FR-MISSION-12 | 계획 | (없음) |
| FR-MISSION-13 | 진행 중 | MissionViewportFilterTest |
| FR-MISSION-14 | 진행 중 | MissionViewportFilterTest |
| FR-MISSION-15 | 계획 | (없음) |
| FR-MISSION-20 | 진행 중 | MissionAggregationHttpTest, MissionAggregationIntegrationTest, MissionPublicAccessHttpTest, MissionRegionAnchorTest |
| FR-MISSION-16 | 진행 중 | CourseMissionSeederIntegrationTest, CourseSeedReaderTest, FestivalJsonlReaderTest, FestivalMissionSeederIntegrationTest, MissionControllerTest, MissionDetailServiceTest, MissionPublicAccessHttpTest, MissionQueryServiceImplTest, MissionSchemaMigrationTest, PopupJsonlReaderTest, PopupMissionSeederIntegrationTest |
| FR-MISSION-17 | 진행 중 | MissionControllerTest, MissionDetailConsistencyTest, MissionDetailServiceTest, MissionPublicAccessHttpTest, MissionVideoControllerTest, MissionVideoCountQueryTest, MissionVideoListQueryTest, MissionVideoListServiceTest, MissionVisitedGridQueryTest |
| FR-MISSION-18 | 진행 중 | MissionControllerTest, MissionDetailConsistencyTest, MissionDetailServiceTest, MissionProgressQueryTest, MissionProgressServiceTest, MissionPublicAccessHttpTest, MissionVisitedGridQueryTest |
| FR-MISSION-19 | 계획 | (없음) |
| FR-EVENT-01 | 구현됨 | EventQueryServiceTest |
| FR-EVENT-02 | 구현됨 | EventQueryServiceTest |
| FR-EVENT-03 | 폐기됨 | (없음) |
| FR-EVENT-04 | 폐기됨 | (없음) |
| FR-EVENT-05 | 폐기됨 | (없음) |
| FR-EVENT-06 | 구현됨 | EventNotificationControllerTest, EventNotificationSchedulerTest, EventNotificationServiceTest, EventNotificationSubscriptionRepositoryTest, EventQueryServiceTest, EventSeederScheduleChangeTest, NotificationConsumerTest, NotificationPreferenceServiceIntegrationTest, OpenApiNullableDataTest |
| FR-EVENT-07 | 구현됨 | EventOccurrenceStatusTest, EventQueryServiceTest, EventSeederTest, EventVideoQueryServiceTest |
| FR-EVENT-08 | 구현됨 | EventQueryServiceTest, EventSeederTest, EventVideoQueryServiceTest, EventVideoUploadConcurrencyTest, EventVideoUploadServiceTest, RepresentativeGridResolverTest |
| FR-EVENT-09 | 구현됨 | EventVideoCommentServiceTest, EventVideoHelpfulServiceTest, EventVideoPublicAccessHttpTest, EventVideoQueryServiceTest, EventVideoUploadServiceTest, EventVideoVisibilityIntegrationTest |
| FR-EVENT-10 | 구현됨 | EventInteractionLockTest, EventLifecycleGuardTest, EventNotificationSubscriptionRepositoryTest, EventSeederScheduleChangeTest, EventVideoQueryServiceTest, EventVideoUploadConcurrencyTest, EventVideoUploadServiceTest |
| FR-EVENT-11 | 구현됨 | EventViewerCacheFailureControllerTest, EventViewerControllerTest, EventViewerServiceImplTest |
| FR-EVENT-12 | 구현됨 | MissionAwardQueryTest, MissionProgressQueryTest, MissionVideoCountQueryTest, MissionVideoListQueryTest, MissionVisitedGridQueryTest |
| FR-NOTI-01 | 구현됨 | AuthControllerTest, AuthServiceTest, PushTokenControllerTest, PushTokenServiceIntegrationTest |
| FR-NOTI-02 | 구현됨 | NotificationCommandServiceIntegrationTest, NotificationConsumerTest, NotificationRelayTest |
| FR-NOTI-03 | 구현됨 | NotificationCommandServiceIntegrationTest, NotificationConsumerTest |
| FR-NOTI-04 | 구현됨 | NotificationConsumerTest |
| FR-NOTI-05 | 구현됨 | FcmNotificationSenderTest, NotificationConsumerTest, PushTokenRepositoryIntegrationTest, StaleTokenCleanerTest |
| FR-NOTI-06 | 구현됨 | NotificationConsumerTest, NotificationPreferenceControllerTest, NotificationPreferenceServiceIntegrationTest |
| FR-NOTI-07 | 구현됨 | NotificationConsumerTest |
| FR-NOTI-08 | 구현됨 | BadgeNotificationIntegrationTest |
| FR-NOTI-09 | 구현됨 | HotZoneEntryDetectorTest |
| FR-NOTI-10 | 구현됨 | VideoNotificationIntegrationTest |
| FR-NOTI-11 | 구현됨 | WeeklySummarySchedulerTest |
| FR-NOTI-12 | 계획 | (없음) |
| FR-NOTI-13 | 폐기됨 | (없음) |
| FR-NOTI-14 | 구현됨 | FriendIntegrationTest, FriendNotificationRollbackTest, NotificationPreferenceServiceIntegrationTest |
| FR-NOTI-15 | 구현됨 | NotificationConsumerTest, NotificationPreferenceServiceIntegrationTest, OpenApiNullableDataTest, VideoBlindIntegrationTest |
| FR-NOTI-16 | 진행 중 | NotificationPreferenceServiceIntegrationTest |
| FR-NOTI-17 | 구현됨 | NotificationInboxControllerTest, NotificationInboxIntegrationTest |
| FR-FRIEND-01 | 구현됨 | FriendControllerTest, FriendIntegrationTest, UserFriendCodeTest |
| FR-FRIEND-02 | 구현됨 | FriendControllerTest, FriendIntegrationTest, FriendshipTest |
| FR-FRIEND-03 | 구현됨 | FriendIntegrationTest |
| FR-FRIEND-04 | 구현됨 | FriendControllerTest, FriendIntegrationTest, FriendshipTest |
| FR-FRIEND-05 | 구현됨 | UserAccountDeletionIntegrationTest |
| FR-FRIEND-06 | 구현됨 | FriendControllerTest, FriendIntegrationTest |
| FR-FRIEND-07 | 구현됨 | FriendControllerTest, FriendProfileIntegrationTest |
| FR-FRIEND-08 | 구현됨 | FriendGridVideosTest, FriendGridViewportTest, FriendProfileIntegrationTest |
| FR-FRIEND-09 | 구현됨 | FriendGridVideosTest, FriendGridViewportTest, FriendIntegrationTest, FriendProfileIntegrationTest |
| FR-FRIEND-10 | 구현됨 | FriendControllerTest, FriendGridViewportTest |
| FR-FRIEND-11 | 구현됨 | FriendControllerTest, FriendGridVideosTest |
| FR-FRIEND-12 | 구현됨 | FriendProfileIntegrationTest |
| FR-FRIEND-13 | 계획 | (없음) |
| FR-MOD-01 | 구현됨 | ReportControllerTest, ReportIntegrationTest |
| FR-MOD-02 | 구현됨 | ReportIntegrationTest |
| FR-MOD-03 | 구현됨 | ReportControllerTest, ReportIntegrationTest |
| FR-MOD-04 | 구현됨 | ReportControllerTest, ReportIntegrationTest |
| FR-MOD-05 | 구현됨 | AdminReportControllerTest, AdminReportIntegrationTest, ReportIntegrationTest |
| FR-MOD-06 | 구현됨 | VideoBlindIntegrationTest |
| FR-MOD-07 | 구현됨 | AdminReportIntegrationTest |
| FR-MOD-08 | 구현됨 | VideoBlindIntegrationTest |
| FR-MOD-09 | 구현됨 | AdminReportControllerTest, AdminReportIntegrationTest |
| FR-MOD-10 | 구현됨 | AdminReportControllerTest, AdminReportIntegrationTest |
| FR-MOD-11 | 구현됨 | AdminReportControllerTest, AdminReportIntegrationTest, ReportTest |
| FR-MOD-12 | 구현됨 | AdminReportApproveConcurrencyTest, AdminReportControllerTest, AdminReportIntegrationTest |
| FR-MOD-13 | 구현됨 | AdminAuthorizationTest, AdminReportIntegrationTest |
| FR-MOD-14 | 계획 | (없음) |
| FR-AUTH-01 | 구현됨 | AuthControllerTest, OidcLoginServiceTest |
| FR-AUTH-02 | 구현됨 | AuthControllerTest, KakaoAuthCodeExchangerTest |
| FR-AUTH-03 | 구현됨 | AuthControllerTest, KakaoAuthCodeExchangerTest |
| FR-AUTH-04 | 구현됨 | KakaoAuthCodeExchangerTest |
| FR-AUTH-05 | 구현됨 | AuthServiceTest, OidcLoginServiceTest, RedisRefreshTokenStoreTest |
| FR-AUTH-06 | 구현됨 | AuthServiceTest, RedisRefreshTokenStoreTest, RefreshTokenServiceTest |
| FR-AUTH-07 | 구현됨 | AuthControllerTest, RefreshTokenServiceTest |
| FR-AUTH-08 | 구현됨 | AuthControllerTest |
| FR-AUTH-09 | 구현됨 | AuthControllerTest, AuthServiceTest, JwtFilterIntegrationTest, JwtTokenProviderTest, RedisInvalidatedTokenStoreTest |
| FR-AUTH-10 | 구현됨 | AuthControllerTest, KakaoAuthCodeExchangerTest |
| FR-AUTH-11 | **미충족** | AuthControllerTest, AuthServiceTest |
| FR-AUTH-12 | 계획 | (없음) |
| FR-USER-01 | 구현됨 | UserProfileControllerTest, UserProfileIntegrationTest |
| FR-USER-02 | 구현됨 | UserProfileControllerTest, UserProfileIntegrationTest |
| FR-USER-03 | 구현됨 | UserEmaillessPersistenceTest |
| FR-USER-04 | 구현됨 | AuthControllerTest, AuthServiceTest, OidcLoginServiceTest, UserEmaillessPersistenceTest, UserProfileControllerTest, UserProfileIntegrationTest |
| FR-USER-05 | 구현됨 | FriendControllerTest, FriendIntegrationTest, FriendProfileIntegrationTest |
| FR-USER-06 | 구현됨 | UserAccountDeletionIntegrationTest, UserRepositoryDeletionTest |
| FR-USER-07 | 구현됨 | PushTokenServiceIntegrationTest, UserAccountDeletionIntegrationTest |
| FR-USER-08 | 구현됨 | UserAccountS3CleanupTest, UserRepositoryDeletionTest |
| FR-USER-09 | 구현됨 | RefreshTokenServiceTest, UserAccountS3CleanupTest, UserAccountSessionInvalidationIntegrationTest |
| FR-USER-10 | 구현됨 | UserAccountDeletionIntegrationTest |
| FR-USER-11 | 구현됨 | UserAccountDeletionIntegrationTest, UserRepositoryDeletionTest |
| FR-USER-12 | 진행 중 | UserAccountS3CleanupTest, UserProfileControllerTest, UserProfileImageIntegrationTest, UserProfileImagePresignTest, UserProfileImageS3Test |
| FR-USER-13 | 진행 중 | UserProfileControllerTest, UserProfileImageIntegrationTest |
| FR-USER-14 | 구현됨 | UserLocationConsentIntegrationTest, UserProfileControllerTest |
| FR-USER-15 | 구현됨 | UserConsentControllerTest, UserConsentIntegrationTest |

## 검증 공백: 구현됐는데 대응 테스트가 없다 (조치 대상)

(없음)

## 미구현(계획)·폐기: 테스트 부재가 정상

- FR-MAP-01 (계획)
- FR-MAP-02 (계획)
- FR-MAP-03 (계획)
- FR-MAP-04 (계획)
- FR-MAP-05 (폐기됨)
- FR-MAP-06 (계획)
- FR-MAP-08 (계획)
- FR-MAP-07 (계획)
- FR-REGION-13 (계획)
- FR-SEARCH-13 (계획)
- FR-SEARCH-14 (계획)
- FR-BADGE-11 (계획)
- FR-MISSION-07 (폐기됨)
- FR-MISSION-12 (계획)
- FR-MISSION-15 (계획)
- FR-MISSION-19 (계획)
- FR-EVENT-03 (폐기됨)
- FR-EVENT-04 (폐기됨)
- FR-EVENT-05 (폐기됨)
- FR-NOTI-12 (계획)
- FR-NOTI-13 (폐기됨)
- FR-FRIEND-13 (계획)
- FR-MOD-14 (계획)
- FR-AUTH-12 (계획)

## 성격상 테스트로 검증하지 않는 요구 (사유 정본: SRS 8장 목록)

- FR-GRID-12: 일회성 이행의 계약 동결 조항이다. 이행이 끝난 지금 검증할 전환 이벤트가 없고, 현행 계약 자체는 각 API 테스트가 본다
- FR-ZONE-14: 격자 체계 전환 때만 발생하는 일회성 이행 절차 요구라 상시 회귀 테스트가 성립하지 않는다. 미래 전환이 사각형 재산출을 잊지 않는 것은 테스트가 아니라 전환 티켓의 이행 절차가 보장할 일이다
- FR-HOTZONE-01: 핫구역의 범위 단위가 격자라는 정의 조항이라 단정할 동작이 없다
- FR-HOTZONE-12: 격자 체계 전환 때의 일회성 처리 방침이고, 이전 신호 폐기 허용은 부정형이라 상시 테스트 대상이 아니다
- FR-VIDEO-06: 서버가 위치를 증명하지 않는다는 부정형 요구다. 하지 않는 동작은 단언할 대상이 없다
- FR-MEDIA-17: 블러 처리는 AI 서버 쪽 판정이라 이 레포의 테스트 범위 밖이다
- FR-MEDIA-16: 응답 시간 목표라 단위 테스트가 아니라 부하 테스트 영역이다. 수치 근거는 MSG-351 실측이다

## 비대상 표기 점검 (표기와 실제가 어긋난 항목)

(없음)

## 테스트에만 있고 SRS에 없는 ID (주석 오타 의심)

(없음)

## 형식이 어긋난 마커 토큰 (매핑 집계에서 무시됨)

(없음)
