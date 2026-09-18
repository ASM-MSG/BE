# 웹 푸시 중복 표시 억제 구현 스펙

승인 근거: [PRD](../prd/notification-display-deduplication.md), 2026-09-17 성민 구현 지시. 티켓 미발행. 검증 프로파일: 로직.

## 계약

- 기존 notifications.id를 문자열 data.notificationId로 전달한다. 발송 재시도와 500개 토큰 청크 분할에서도 같은 값을 쓴다.
- notification 제목·본문은 유지한다. 웹 전용 표시 옵션은 tag=`fillmap-notification-{id}`, renotify=false다. 모바일 표시 계약은 유지한다.
- notification 메시지는 FCM SDK가 배경 표시를 맡고 웹 서비스 워커는 다시 표시하지 않는다. data-only 메시지는 기존 수동 표시 경로를 유지한다.
- 전경은 현재 열린 토스트 중 같은 notificationId가 있으면 추가하지 않는다. 식별자가 없는 구형 메시지는 그대로 추가한다. 닫기 이후·새로고침·다중 탭 전체의 영구 중복 방지는 범위 밖이다.

## 수용 기준

1. DB 완료 기록 실패 후 재발송에도 같은 식별자가 전달된다. 외부 발송 횟수는 여전히 2회일 수 있다.
2. 전경 같은 알림 100회 수신은 토스트 1개, 다른 식별자 2개는 같은 본문이어도 2개다.
3. notification 수신 때 수동 표시 0회, 구형 data-only 수신은 수동 표시한다.
4. 실제 브라우저에서 같은 tag 알림 100회는 현재 항목 1개다. 서로 다른 tag는 별개다.
5. 실제 FCM 수신 확인과 브라우저 주입 시험을 구분한다. 실제 수신 환경이 없으면 종단 간[^3] 완료라고 기록하지 않는다.

## 저장과 실패 경계

새 DB·Redis 저장은 없다. 식별자는 기존 outbox[^1] 행의 ID를 쓴다. 웹 전경의 중복 판단은 React 상태, 배경의 현재 알림 교체는 브라우저 tag를 사용한다. FCM 외부 전송과 DB 종결 사이 원자성[^2] 한계는 유지되며 DLQ를 추가하지 않는다. 소비자의 재시도·종결 전이·기존 오류 처리는 유지한다.

FE는 `/Users/ssomae/seongmin/coding/fillmap-FE-push-dedupe`의 `feature/notification-display-deduplication`에서 작업한다. BE는 기존 검증 브랜치에서 이어간다. 문서 조회: 위키 알림 예정 API, 기존 MSG-179 스펙·알림 PRD. 구현 사실은 현재 코드를 따른다.

[^1]: outbox는 업무 처리와 함께 저장하는 발송 대기 기록이다.
[^2]: 원자성은 여러 작업이 모두 성공하거나 모두 실패하는 성질이다.
[^3]: 종단 간 검증은 서버 발송부터 실제 기기 수신과 표시까지 확인하는 시험이다.


## 작업 로그와 검증 (2026-09-17)

- BE: 기존 NotificationSender에 알림 ID 인자를 추가했다. Kafka 소비자는 DB ID를 넘기고, 발송기는 data.notificationId와 webpush.notification.tag를 구성한다. 기존 제목·본문과 500개 청크 분할·부분 성공 처리는 유지한다.
- FE: SDK 자동 표시 메시지는 수동 표시하지 않는다. 전경은 현재 토스트의 notificationId를 비교한다. 영구 수신 이력·새 의존성은 추가하지 않았다. 닫기 이후 억제는 승인 범위 밖이다.
- 저장: notifications.id 재사용, 새 DB 컬럼·Redis 키·TTL·S3 객체 없음. React 현재 상태와 브라우저의 기존 알림 목록만 쓴다.
- SQL: 기존 native 종결 쿼리 `UPDATE notifications SET status = 'SENT', sent_at = statement_timestamp() AT TIME ZONE 'UTC' WHERE id = :id AND status IN ('PENDING', 'PUBLISHED')`는 그대로다. 실제 DB 트리거 장애 시험에서 첫 실패 후 재발송 2회에 같은 ID가 전달되는 것을 확인했다.
- 예외: markSent의 DB 예외는 소비자 밖으로 전파돼 기존 Kafka 오류 처리기로 들어간다. 새 HTTP API·developCode·DLQ는 없다. 발송 전에 SENT로 바꾸지 않는다.
- 빈: 기존 조건부 발송기·소비자 생명주기와 트랜잭션 경계를 유지한다. 웹은 같은 SDK 구독을 사용하고 해지 뒤 늦게 도착한 콜백은 무시한다.

| 검증 | 결과 |
|---|---|
| 서버 알림·릴레이·실제 DB 장애·발송 payload | 32건 통과, 빌드 통과 |
| 웹 전체 단위·통합 테스트 | 2,577건 통과 |
| 웹·모바일 전체 타입 검사, 린트, 포맷 검사 | 통과 |
| 웹 프로덕션 빌드 | 통과. 기존 동적 import 경고 있음 |
| 실제 Chrome, 실제 Firebase SDK 수신 경계와 전경 훅 | 2건 통과. 100회 재전달은 토스트 1개, 다른 ID는 별도 표시 |
| OS 알림 목록의 tag 교체 | 미통과. headless 권한 오류, headed Chromium·Chrome은 목록이 비어 있음. 환경 원인은 확정하지 못함 |
| 실제 FCM 서버 → 단말 수신 | 미실행. 브라우저 시험은 service-worker message 이벤트를 주입한 검증 |
| 모바일 회귀 테스트 | 1,378건 통과, 업로드 위치 라벨 동등성 1건 sessionStorage 미정의로 실패. 해당 경로 수정 없음 |
| 중복 코드 검사 | nose 실행 파일이 없어 실행 불가. 신규 제품 코드의 중복 여부는 별도 수동 점검 |

수정 전 전경 100회 수신은 100개로 쌓였고 배경 notification 콜백은 수동 표시를 호출해 신규 테스트 2건이 실패했다. 수정 후 4개 신규 웹 단위 테스트를 포함해 전체 웹 테스트가 통과했다. 초기 SDK 모킹 테스트는 실제 동적 import 경계를 일관되게 대체하지 못해 제거하고, 실제 브라우저 SDK 경계 검증으로 대체했다. 서버 시험의 초기 추가 단정은 100개 서로 다른 알림에도 첫 ID를 강제해 실패했으며, 재발송 대상 두 호출에 같은 ID가 전달되는 단정으로 수정했다.

별도 검토에서 인터페이스 선언·발송 구현·소비자·모든 테스트 호출부를 대조했다. 기존 재시도·종결 상태·토큰 삭제 로직이 보존되고, 브라우저에서 업무 ID와 FCM 전송 ID가 혼동되지 않는 것을 확인했다. UI 배치·접근성·디자인 변경은 없어 로직 프로파일로 검증했다.

원자료: `load-test/evidence/2026-09-17/push-display/`. 검증 불가 항목을 성공으로 간주하지 않으므로 “모든 외부 푸시 중복 0”은 주장할 수 없다. dev·운영 배포 및 커밋은 수행하지 않았다.

## 재현과 배포 순서

- BE: `./gradlew -I load-test/tx-workload/verification.init.gradle test --tests '*FcmNotificationSenderTest' --tests '*NotificationConsumerTest' --tests '*NotificationCrashWindowTest' --tests '*NotificationRelayTest'`.
- FE: 웹 작업 공간에서 `pnpm --filter web test run`, `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm build`.
- 실브라우저: `pnpm --filter web exec playwright test --config push-verification.config.ts`. OS 알림 가능한 Chrome 환경이 필요하다. `--grep '실제'`는 SDK·전경 2건만 실행하며 OS 검증을 대체하지 않는다.
- 웹을 먼저 배포하고 기존 서비스 워커의 새 버전 활성화를 확인한 뒤 서버를 배포한다. 구형 워커가 남아 있으면 자동·수동 이중 표시 경로가 남는다. 실제 FCM 수신 검증 후 배경 표시 수용 기준을 완료 판정한다.

## 커밋 계획

1. BE 서버·테스트: `MSG-179 fix: 푸시 재발송에도 같은 알림 식별자를 전달한다`.
2. FE 웹·검증: 티켓 미발행. 번호 확정 후 별도 커밋하며 알림 ID 수신과 표시 억제, 전용 브라우저 검증을 묶는다.
3. PRD·스펙·SRS·실측 자료는 코드 커밋과 구분한다. 이전 부하 검증 파일은 이번 서버 커밋에 섞지 않는다.

첫 준비 명령만 제시한다. 직접 실행하거나 커밋하지 않았다.

```bash
git add src/main/java/com/msg/fillmap/notification/consumer/NotificationConsumer.java src/main/java/com/msg/fillmap/notification/sender/NotificationSender.java src/main/java/com/msg/fillmap/notification/sender/FcmNotificationSender.java src/test/java/com/msg/fillmap/notification/consumer/NotificationConsumerTest.java src/test/java/com/msg/fillmap/notification/sender/FcmNotificationSenderTest.java
```

적용 스킬: prd-writer 승인 기록, srs-writer 보장 범위 갱신, fillmap-page-dev의 page-implementation·page-verification, korean-humanizer, tech-term-footnotes. 지정 모델을 사용할 수 없어 위임 없이 동일 세션에서 구현과 검토를 나눴다. 외부 게시·메시지 전송은 하지 않았다.
