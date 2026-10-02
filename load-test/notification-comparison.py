#!/usr/bin/env python3
"""알림 발송 구조 3단 비교 — 동기 직접 발송 / DB 폴링 릴레이 / outbox + Kafka + 컨슈머(현재 구현).

격리 컨테이너 전용 하네스다 (fillmap-notif-compare-pg 127.0.0.1:15439, fillmap-notif-compare-kafka 127.0.0.1:19092).
dev/prod·기존 fillmap-* 컨테이너에는 붙지 않는다. FCM 은 이 파일 안의 스텁 HTTP 서버(별도 프로세스)로 대체한다.

의존성: psycopg[binary] confluent-kafka (임시 venv). 실측·해석은
docs/reports/2026-09-30-notification-dispatch-comparison.md.

비교군은 "알림 발생 요청 → 단말 도착" 경로를 Python 으로 모사한 것이지 Spring 앱을 띄운 게 아니다.
  sync          ① 요청 핸들러가 행을 넣고 같은 요청 안에서 FCM 을 부른 뒤 응답. FCM 실패 = 요청 실패(DEAD, 재시도 없음)
  db_poll       ② 요청은 행만 넣고 응답. 폴러가 1초마다 FOR UPDATE SKIP LOCKED 로 100건 집어 FCM 호출 후 SENT.
                   실패는 retry_count + next_attempt_at(지수 백오프, 컨슈머와 같은 상수) — V21 에 없는 컬럼이라 이 모드 전용
  outbox_kafka  ③ 요청은 알림 행(= outbox 행)을 넣고 응답. 릴레이가 코드 주기(5s/100건)로 Kafka 발행(send().get() 동기),
                   컨슈머가 NotificationConsumer.consume 순서대로 처리하고 실패는 DefaultErrorHandler 백오프(1s×2^n, 8회) 후 DEAD
  outbox_kafka_1s  ③에서 릴레이 주기만 1s 로 바꾼 보조 비교군 — "③의 버스트 소화 시간이 Kafka 가 아니라 릴레이 상수에
                   묶였는가"를 가르기 위한 것. 기본 실행에는 없다 (--modes 로 추가)

컨슈머 병렬 2개(④)는 파티션 1 이라 두 번째 컨슈머가 파티션을 못 받아 유휴 — 비교군에서 뺐다 (보고서 참조).
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import gzip
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import multiprocessing
from pathlib import Path
import platform
import re
import statistics
import subprocess
import threading
import time
import uuid

import psycopg
from confluent_kafka import Consumer as KafkaConsumer, KafkaException, Producer
from confluent_kafka.admin import AdminClient, NewTopic

ROOT = Path(__file__).resolve().parents[1]
DSN = "host=127.0.0.1 port=15439 dbname=notif_bench user=bench password=bench"
KAFKA = "127.0.0.1:19092"
PG_CONTAINER = "fillmap-notif-compare-pg"
KAFKA_CONTAINER = "fillmap-notif-compare-kafka"
FCM_PORT = 18093
FCM_DELAY_MS = 30
DB_POLL_INTERVAL_MS = 1000   # ② 전용 — 코드에 없는 값, 과제 조건
DB_POLL_BATCH = 100
USERS = 20000                # 1..10000 은 행사 구독자(W2·W3 버스트), 10001.. 은 W1 형 요청용
SUBSCRIBERS = 10000
OCCURRENCE_ID = 1
SOURCES = {
    "yml": "src/main/resources/application.yml",
    "config": "src/main/java/com/msg/fillmap/notification/config/NotificationConfig.java",
    "relay": "src/main/java/com/msg/fillmap/notification/relay/NotificationRelay.java",
    "consumer": "src/main/java/com/msg/fillmap/notification/consumer/NotificationConsumer.java",
    "repository": "src/main/java/com/msg/fillmap/notification/repository/NotificationRepository.java",
    "sender": "src/main/java/com/msg/fillmap/notification/sender/FcmNotificationSender.java",
}


def constants_from_source():
    """앱 상수를 소스에서 읽어 하네스가 같은 값을 쓰는지 강제한다 (hotzone-comparison.source_script 선례)."""
    yml = (ROOT / SOURCES["yml"]).read_text()
    config = (ROOT / SOURCES["config"]).read_text()
    relay = (ROOT / SOURCES["relay"]).read_text()

    def grab(pattern, text, cast=int):
        match = re.search(pattern, text)
        assert match, pattern
        return cast(match.group(1))

    constants = {
        "relay_poll_interval_ms": grab(r"poll-interval-ms:\s*(\d+)", yml),
        "relay_batch_size": grab(r"batch-size:\s*(\d+)", yml),
        "relay_stale_published_minutes": grab(r"stale-published-minutes:\s*(\d+)", yml),
        "consumer_retry_initial_ms": grab(r"RETRY_INITIAL_INTERVAL_MS\s*=\s*(\d+)L", config),
        "consumer_retry_multiplier": grab(r"RETRY_MULTIPLIER\s*=\s*([\d.]+)", config, float),
        "consumer_retry_max_retries": grab(r"RETRY_MAX_RETRIES\s*=\s*(\d+)", config),
        "consumer_retry_max_interval_ms": grab(r"RETRY_MAX_INTERVAL_MS\s*=\s*([\d_]+)L", config,
                                               lambda s: int(s.replace("_", ""))),
        "consumer_max_poll_records": grab(r"MAX_POLL_RECORDS_CONFIG,\s*(\d+)", config),
        "consumer_max_poll_interval_ms": grab(r"MAX_POLL_INTERVAL_MS_CONFIG,\s*([\d_]+)", config,
                                              lambda s: int(s.replace("_", ""))),
        "topic_partitions": grab(r"\.partitions\((\d+)\)", config),
        "fcm_multicast_limit": grab(r"MULTICAST_LIMIT\s*=\s*(\d+)", (ROOT / SOURCES["sender"]).read_text()),
    }
    assert "fixedDelayString" in relay, "릴레이가 fixedDelay 가 아니면 폴러 모사 방식을 바꿔야 한다"
    pending_sql = re.search(r'SELECT \* FROM notifications WHERE status = .PENDING. ORDER BY id LIMIT :limit[^"]*',
                            (ROOT / SOURCES["repository"]).read_text()).group(0)
    assert "FOR UPDATE" not in pending_sql, "릴레이 배치 조회가 클레임 잠금을 쓰기 시작했다 — ③ 모사를 갱신할 것"
    return constants


CONST = constants_from_source()
BACKOFF_MS = [min(int(CONST["consumer_retry_initial_ms"] * CONST["consumer_retry_multiplier"] ** i),
                  CONST["consumer_retry_max_interval_ms"]) for i in range(CONST["consumer_retry_max_retries"])]
# ExponentialBackOffWithMaxRetries(8): 1,2,4,8,16,32,64,128s — 첫 시도 실패 후 8회 재시도, 합 255s.
assert BACKOFF_MS == [1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000], BACKOFF_MS


# ---------------------------------------------------------------------------------------------- FCM 스텁
class _StubState:
    def __init__(self, delay_ms):
        self.lock = threading.Lock()
        self.delay_ms = delay_ms
        self.outage_until = 0.0
        self.outage_end_wall = None
        self.first_ok_after_outage = None
        self.reset()

    def reset(self):
        self.calls = 0
        self.ok = 0
        self.rejected = 0
        self.per_id = Counter()
        self.first_ok_after_outage = None
        self.outage_end_wall = None


def _stub_handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def _json(self, code, payload):
            body = json.dumps(payload).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            payload = json.loads(self.rfile.read(length) or b"{}")
            if self.path == "/send":
                time.sleep(state.delay_ms / 1000)
                now = time.monotonic()
                with state.lock:
                    state.calls += 1
                    if now < state.outage_until:
                        state.rejected += 1
                        self._json(503, {"error": "UNAVAILABLE"})
                        return
                    state.ok += 1
                    state.per_id[int(payload["id"])] += 1
                    if state.outage_end_wall and state.first_ok_after_outage is None:
                        state.first_ok_after_outage = time.time()
                self._json(200, {"successCount": len(payload["tokens"]), "failureCount": 0})
            elif self.path == "/control":
                with state.lock:
                    if "outage_s" in payload:
                        state.outage_until = time.monotonic() + payload["outage_s"]
                        state.outage_end_wall = time.time() + payload["outage_s"]
                        state.first_ok_after_outage = None
                    if payload.get("reset"):
                        state.reset()
                    if "delay_ms" in payload:
                        state.delay_ms = payload["delay_ms"]
                self._json(200, {"ok": True})
            else:
                self._json(404, {})

        def do_GET(self):
            with state.lock:
                dup_ids = [i for i, n in state.per_id.items() if n > 1]
                stats = {"calls": state.calls, "ok": state.ok, "rejected": state.rejected,
                         "distinct_ok_ids": len(state.per_id), "duplicate_ids": len(dup_ids),
                         "duplicate_extra_deliveries": sum(state.per_id[i] - 1 for i in dup_ids),
                         "max_per_id": max(state.per_id.values(), default=0),
                         "outage_end_wall": state.outage_end_wall,
                         "first_ok_after_outage_wall": state.first_ok_after_outage}
                if self.path == "/ids":
                    stats["ok_ids"] = sorted(state.per_id)
            self._json(200, stats)

    return Handler


def serve_stub(port, delay_ms):
    server = ThreadingHTTPServer(("127.0.0.1", port), _stub_handler(_StubState(delay_ms)))
    server.daemon_threads = True
    server.serve_forever()


class FcmFailure(Exception):
    """FCM 503 — 컨슈머의 IllegalStateException("FCM 발송 전부 실패") 에 해당한다."""


class CrashInjected(Exception):
    """W4: FCM 성공 직후·SENT 기록 전 워커 사망 모사."""


_local = threading.local()


def _fcm_conn():
    if not hasattr(_local, "fcm"):
        _local.fcm = http.client.HTTPConnection("127.0.0.1", FCM_PORT, timeout=30)
    return _local.fcm


def fcm_call(path, payload):
    body = json.dumps(payload)
    for attempt in range(2):
        conn = _fcm_conn()
        try:
            conn.request("POST", path, body, {"Content-Type": "application/json"})
            response = conn.getresponse()
            data = response.read()
            return response.status, json.loads(data or b"{}")
        except (http.client.HTTPException, OSError):
            conn.close()
            del _local.fcm
            if attempt:
                raise


def fcm_send(notification_id, tokens):
    status, _ = fcm_call("/send", {"id": notification_id, "tokens": tokens})
    return status == 200


def fcm_stats(with_ids=False):
    conn = http.client.HTTPConnection("127.0.0.1", FCM_PORT, timeout=30)
    conn.request("GET", "/ids" if with_ids else "/stats")
    data = json.loads(conn.getresponse().read())
    conn.close()
    return data


def fcm_control(**payload):
    fcm_call("/control", payload)


# ---------------------------------------------------------------------------------------------- DB
SCHEMA = """
DROP TABLE IF EXISTS notifications, push_tokens, notification_opt_outs, event_notification_subscriptions;
CREATE TABLE push_tokens (fcm_token VARCHAR(255) PRIMARY KEY, user_id BIGINT NOT NULL);
CREATE INDEX idx_push_tokens_user ON push_tokens (user_id);
CREATE TABLE notification_opt_outs (user_id BIGINT NOT NULL, category VARCHAR(20) NOT NULL,
	PRIMARY KEY (user_id, category));
CREATE TABLE event_notification_subscriptions (event_occurrence_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
	created_at TIMESTAMP NOT NULL);
CREATE INDEX idx_event_noti_sub_occurrence ON event_notification_subscriptions (event_occurrence_id);
-- V21__notifications.sql 사본. users FK 만 뺐고(사용자 테이블 없음) next_attempt_at 은 ② 전용 추가 컬럼이다.
CREATE TABLE notifications (
	id           BIGSERIAL    PRIMARY KEY,
	user_id      BIGINT       NOT NULL,
	category     VARCHAR(10)  NOT NULL,
	event_key    VARCHAR(100) NOT NULL,
	title        VARCHAR(100) NOT NULL,
	body         VARCHAR(255) NOT NULL,
	status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
	retry_count  INT          NOT NULL DEFAULT 0,
	last_error   VARCHAR(255),
	created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
	published_at TIMESTAMP,
	sent_at      TIMESTAMP,
	next_attempt_at TIMESTAMP,
	CONSTRAINT chk_notifications_status CHECK (status IN ('PENDING', 'PUBLISHED', 'SENT', 'SKIPPED', 'DEAD')),
	CONSTRAINT uq_notifications_user_event UNIQUE (user_id, event_key)
);
CREATE INDEX idx_notifications_pending ON notifications (id) WHERE status = 'PENDING';
CREATE INDEX idx_notifications_published ON notifications (published_at) WHERE status = 'PUBLISHED';
CREATE INDEX idx_notifications_user ON notifications (user_id);
"""
INSERT_ONE = """
INSERT INTO notifications (user_id, category, event_key, title, body, created_at, next_attempt_at)
VALUES (%s, %s, %s, 'title', 'body', statement_timestamp() AT TIME ZONE 'UTC', NULL)
ON CONFLICT (user_id, event_key) DO NOTHING RETURNING id"""
INSERT_EVENT_START = """
INSERT INTO notifications (user_id, category, event_key, title, body, created_at)
SELECT s.user_id, 'EVENT', %s, 'title', 'body', statement_timestamp() AT TIME ZONE 'UTC'
FROM event_notification_subscriptions s
WHERE s.event_occurrence_id = %s AND s.created_at <= %s
ON CONFLICT (user_id, event_key) DO NOTHING"""
MARK_SENT = """UPDATE notifications SET status = 'SENT', sent_at = statement_timestamp() AT TIME ZONE 'UTC'
WHERE id = %s AND status IN ('PENDING', 'PUBLISHED')"""
MARK_DEAD = """UPDATE notifications SET status = 'DEAD', last_error = left(%s, 255)
WHERE id = %s AND status IN ('PENDING', 'PUBLISHED')"""
MARK_PUBLISHED = """UPDATE notifications SET status = 'PUBLISHED', published_at = statement_timestamp() AT TIME ZONE 'UTC'
WHERE id = %s AND status = 'PENDING'"""
RESET_STALE = "UPDATE notifications SET status = 'PENDING' WHERE status = 'PUBLISHED' AND published_at < %s"
FIND_PENDING_BATCH = "SELECT id FROM notifications WHERE status = 'PENDING' ORDER BY id LIMIT %s"
CLAIM_BATCH = """SELECT id, user_id, category, retry_count FROM notifications
WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= statement_timestamp() AT TIME ZONE 'UTC')
ORDER BY id LIMIT %s FOR UPDATE SKIP LOCKED"""


def pg():
    return psycopg.connect(DSN, autocommit=True)


def setup_schema(conn):
    conn.execute(SCHEMA)
    with conn.cursor().copy("COPY push_tokens (fcm_token, user_id) FROM STDIN") as copy:
        for user in range(1, USERS + 1):
            copy.write_row((f"tok-{user}", user))
    with conn.cursor().copy(
            "COPY event_notification_subscriptions (event_occurrence_id, user_id, created_at) FROM STDIN") as copy:
        for user in range(1, SUBSCRIBERS + 1):
            copy.write_row((OCCURRENCE_ID, user, datetime(2026, 9, 1)))
    conn.execute("VACUUM (ANALYZE) push_tokens, event_notification_subscriptions")


def truncate(conn):
    conn.execute("TRUNCATE notifications RESTART IDENTITY")


crash = {"armed": False, "fired": 0}


def deliver(conn, notification_id, user_id, category):
    """NotificationConsumer.consume 의 처리 순서 — retry_count+1 → 설정 필터 → 토큰 조회 → FCM. 성공 여부를 돌려준다."""
    conn.execute("UPDATE notifications SET retry_count = retry_count + 1 WHERE id = %s", (notification_id,))
    if conn.execute("SELECT 1 FROM notification_opt_outs WHERE user_id = %s AND category = %s",
                    (user_id, category)).fetchone():
        conn.execute("UPDATE notifications SET status = 'SKIPPED', last_error = 'PREF_OFF' WHERE id = %s",
                     (notification_id,))
        return None
    tokens = [row[0] for row in conn.execute("SELECT fcm_token FROM push_tokens WHERE user_id = %s", (user_id,))]
    if not tokens:
        conn.execute("UPDATE notifications SET status = 'SKIPPED', last_error = 'NO_TOKEN' WHERE id = %s",
                     (notification_id,))
        return None
    ok = fcm_send(notification_id, tokens)
    if ok and crash["armed"]:
        crash["armed"] = False
        crash["fired"] += 1
        raise CrashInjected(notification_id)
    return ok


# ---------------------------------------------------------------------------------------------- 요청 핸들러
class Requester:
    def __init__(self, mode):
        self.mode = mode

    def _conn(self):
        if not hasattr(_local, "pg"):
            _local.pg = pg()
        return _local.pg

    def request(self, user_id, event_key, category="VIDEO"):
        """알림 1건을 만드는 요청. 성공이면 None, 실패면 사유 문자열."""
        conn = self._conn()
        row = conn.execute(INSERT_ONE, (user_id, category, event_key)).fetchone()
        if self.mode != "sync":
            return None
        if row is None:
            # 클라이언트 재시도(W4): 행이 이미 있으면 종결 안 된 경우에만 다시 보낸다.
            existing = conn.execute("SELECT id, status FROM notifications WHERE user_id = %s AND event_key = %s",
                                    (user_id, event_key)).fetchone()
            if existing[1] != 'PENDING':
                return None
            notification_id = existing[0]
        else:
            notification_id = row[0]
        return self.send_inline(conn, notification_id, user_id, category)

    @staticmethod
    def send_inline(conn, notification_id, user_id, category):
        ok = deliver(conn, notification_id, user_id, category)
        if ok is None:
            return None
        if ok:
            conn.execute(MARK_SENT, (notification_id,))
            return None
        conn.execute(MARK_DEAD, ("FCM 503 UNAVAILABLE (sync, no retry)", notification_id))
        return "fcm_503"

    def event_start(self, event_key):
        """행사 시작 팬아웃 — INSERT SELECT 한 문장 (NotificationRepository.insertEventStart). ①은 이어서 순차 발송."""
        conn = self._conn()
        began = time.perf_counter()
        inserted = conn.execute(INSERT_EVENT_START, (event_key, OCCURRENCE_ID, datetime(2026, 9, 30))).rowcount
        insert_ms = (time.perf_counter() - began) * 1000
        failures = 0
        if self.mode == "sync":
            rows = conn.execute("SELECT id, user_id, category FROM notifications WHERE event_key = %s ORDER BY id",
                                (event_key,)).fetchall()
            for notification_id, user_id, category in rows:
                if self.send_inline(conn, notification_id, user_id, category):
                    failures += 1
        return {"inserted": inserted, "insert_ms": insert_ms, "request_ms": (time.perf_counter() - began) * 1000,
                "inline_failures": failures}


# ---------------------------------------------------------------------------------------------- 워커
class Worker(threading.Thread):
    def __init__(self, name):
        super().__init__(name=name, daemon=True)
        self.stop_event = threading.Event()
        self.crashed = False
        self.error = None
        self.stats = Counter()

    def stop(self):
        self.stop_event.set()
        self.join(timeout=60)


class DbPoller(Worker):
    """② — 1초 fixedDelay, FOR UPDATE SKIP LOCKED 로 100건 클레임, 트랜잭션 하나 안에서 발송 후 커밋."""

    def run(self):
        conn = pg()
        try:
            while not self.stop_event.is_set():
                try:
                    with conn.transaction():
                        rows = conn.execute(CLAIM_BATCH, (DB_POLL_BATCH,)).fetchall()
                        self.stats["polls"] += 1
                        for notification_id, user_id, category, retry_count in rows:
                            ok = deliver(conn, notification_id, user_id, category)
                            if ok is None:
                                self.stats["skipped"] += 1
                            elif ok:
                                conn.execute(MARK_SENT, (notification_id,))
                                self.stats["sent"] += 1
                            else:
                                attempts = retry_count + 1
                                if attempts > CONST["consumer_retry_max_retries"]:
                                    conn.execute(MARK_DEAD, ("FCM 503 after retries", notification_id))
                                    self.stats["dead"] += 1
                                else:
                                    conn.execute("""UPDATE notifications SET last_error = 'FCM 503',
                                        next_attempt_at = (statement_timestamp() AT TIME ZONE 'UTC') + %s * INTERVAL '1 ms'
                                        WHERE id = %s""", (BACKOFF_MS[attempts - 1], notification_id))
                                    self.stats["retry_scheduled"] += 1
                except CrashInjected:
                    self.crashed = True   # 트랜잭션 롤백 = 잠금 해제·retry_count 원복, 프로세스 사망 모사
                    return
                self.stop_event.wait(DB_POLL_INTERVAL_MS / 1000)
        except Exception as exc:  # 관측용 — 워커가 조용히 죽지 않게 한다
            self.error = repr(exc)
            raise
        finally:
            conn.close()


class Relay(Worker):
    """③ 릴레이 — NotificationRelay.relay() 축자. 스테일 복구 → PENDING 배치 → 행별 send().get() → markPublished."""

    def __init__(self, topic, poll_ms):
        super().__init__("relay")
        self.topic = topic
        self.poll_ms = poll_ms
        self.producer = Producer({"bootstrap.servers": KAFKA, "acks": "all", "enable.idempotence": True,
                                  "linger.ms": 0})

    def publish(self, notification_id):
        result = {}

        def on_delivery(err, _msg):
            result["err"] = err

        self.producer.produce(self.topic, value=str(notification_id), on_delivery=on_delivery)
        self.producer.flush(30)
        if result.get("err") is not None or "err" not in result:
            raise KafkaException(result.get("err", "flush timeout"))

    def run(self):
        conn = pg()
        try:
            while not self.stop_event.is_set():
                conn.execute(RESET_STALE, (datetime.now(timezone.utc).replace(tzinfo=None)
                                           - timedelta(minutes=CONST["relay_stale_published_minutes"]),))
                batch = [row[0] for row in conn.execute(FIND_PENDING_BATCH, (CONST["relay_batch_size"],))]
                self.stats["polls"] += 1
                for notification_id in batch:
                    try:
                        self.publish(notification_id)
                    except KafkaException as exc:
                        self.stats["publish_failed_batches"] += 1
                        self.error = repr(exc)
                        break
                    conn.execute(MARK_PUBLISHED, (notification_id,))
                    self.stats["published"] += 1
                self.stop_event.wait(self.poll_ms / 1000)
        finally:
            conn.close()


class KafkaWorker(Worker):
    """③ 컨슈머 — NotificationConsumer.consume + DefaultErrorHandler(지수 백오프 8회 → DEAD). 오프셋은 poll 배치 처리 후 커밋."""

    def __init__(self, topic, group):
        super().__init__("consumer")
        self.topic = topic
        self.group = group
        self.assigned = threading.Event()

    def process(self, conn, notification_id):
        row = conn.execute("SELECT user_id, category, status FROM notifications WHERE id = %s",
                           (notification_id,)).fetchone()
        if row is None:
            self.stats["missing_row"] += 1
            return
        user_id, category, status = row
        if status not in ("PENDING", "PUBLISHED"):
            self.stats["terminal_replay_blocked"] += 1   # FR-6 2차 멱등 — 재전달 재발송 차단
            return
        ok = deliver(conn, notification_id, user_id, category)
        if ok is None:
            self.stats["skipped"] += 1
            return
        if not ok:
            raise FcmFailure(notification_id)
        if conn.execute(MARK_SENT, (notification_id,)).rowcount == 1:
            self.stats["sent"] += 1

    def run(self):
        consumer = KafkaConsumer({"bootstrap.servers": KAFKA, "group.id": self.group, "enable.auto.commit": False,
                                  "auto.offset.reset": "earliest",
                                  "max.poll.interval.ms": CONST["consumer_max_poll_interval_ms"]})
        consumer.subscribe([self.topic], on_assign=lambda c, parts: self.assigned.set())
        conn = pg()
        try:
            while not self.stop_event.is_set():
                messages = consumer.consume(num_messages=CONST["consumer_max_poll_records"], timeout=0.5)
                for message in messages:
                    if message.error():
                        self.error = str(message.error())
                        continue
                    notification_id = int(message.value())
                    for attempt in range(CONST["consumer_retry_max_retries"] + 1):
                        try:
                            self.process(conn, notification_id)
                            break
                        except FcmFailure:
                            if attempt == CONST["consumer_retry_max_retries"]:
                                conn.execute(MARK_DEAD, ("FCM 503 after retries", notification_id))
                                self.stats["dead"] += 1
                            else:
                                self.stats["backoff_sleeps"] += 1
                                self.stats["backoff_ms_total"] += BACKOFF_MS[attempt]
                                time.sleep(BACKOFF_MS[attempt] / 1000)   # 리스너 스레드 sleep — 파티션 전체 정지
                        except CrashInjected:
                            self.crashed = True
                            return   # 커밋 없이 종료 = 오프셋 미커밋, 재기동 시 재전달
                if messages:
                    consumer.commit(asynchronous=False)
        finally:
            consumer.close()   # enable.auto.commit=false 라 크래시 경로에서도 close 가 오프셋을 커밋하지 않는다
            conn.close()


class Pipeline:
    """모드별 백그라운드 구성요소 묶음 — start/stop/restart_crashed."""

    def __init__(self, mode, label):
        self.mode = mode
        self.label = label
        self.workers = []
        self.topic = self.group = None

    def start(self):
        if self.mode == "db_poll":
            self.workers = [DbPoller("db-poller")]
        elif self.mode.startswith("outbox_kafka"):
            self.topic = f"notif.{self.label}.{uuid.uuid4().hex[:8]}"
            self.group = f"g-{self.topic}"
            admin = AdminClient({"bootstrap.servers": KAFKA})
            for future in admin.create_topics([NewTopic(self.topic, num_partitions=CONST["topic_partitions"],
                                                        replication_factor=1)]).values():
                future.result(30)
            poll_ms = 1000 if self.mode == "outbox_kafka_1s" else CONST["relay_poll_interval_ms"]
            consumer = KafkaWorker(self.topic, self.group)
            self.workers = [Relay(self.topic, poll_ms), consumer]
        for worker in self.workers:
            worker.start()
        for worker in self.workers:
            if isinstance(worker, KafkaWorker):
                assert worker.assigned.wait(30), "컨슈머 파티션 배정 대기 초과"

    def restart_crashed(self):
        replaced = []
        for index, worker in enumerate(self.workers):
            if not worker.crashed:
                continue
            worker.join(timeout=30)
            if isinstance(worker, DbPoller):
                self.workers[index] = DbPoller("db-poller")
            elif isinstance(worker, KafkaWorker):
                self.workers[index] = KafkaWorker(self.topic, self.group)
            else:
                continue
            self.workers[index].start()
            replaced.append(self.workers[index])
        for worker in replaced:
            if isinstance(worker, KafkaWorker):
                assert worker.assigned.wait(30)
        return replaced

    def stop(self):
        for worker in self.workers:
            worker.stop()

    def stats(self):
        return {worker.name: dict(worker.stats) | ({"error": worker.error} if worker.error else {})
                for worker in self.workers}


# ---------------------------------------------------------------------------------------------- 계측 유틸
def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


def stats_of(values):
    return {"n": len(values), "p50_ms": percentile(values, .5), "p95_ms": percentile(values, .95),
            "p99_ms": percentile(values, .99), "max_ms": max(values) if values else None,
            "mean_ms": statistics.fmean(values) if values else None}


def cgroup_cpu(container):
    try:
        text = subprocess.check_output(["docker", "exec", container, "cat", "/sys/fs/cgroup/cpu.stat"], text=True,
                                       timeout=20)
        return int(re.search(r"usage_usec (\d+)", text).group(1)) / 1e6
    except Exception:
        return None


def db_activity(conn):
    row = conn.execute("""SELECT xact_commit, xact_rollback, tup_inserted, tup_updated, tup_returned, tup_fetched
                          FROM pg_stat_database WHERE datname = current_database()""").fetchone()
    return dict(zip(("xact_commit", "xact_rollback", "tup_inserted", "tup_updated", "tup_returned", "tup_fetched"),
                    row))


def resources(conn):
    return {"pg_cpu_s": cgroup_cpu(PG_CONTAINER), "kafka_cpu_s": cgroup_cpu(KAFKA_CONTAINER), "db": db_activity(conn),
            "wall": time.time()}


def resource_delta(before, after):
    return {"pg_cpu_seconds": None if None in (before["pg_cpu_s"], after["pg_cpu_s"]) else
            round(after["pg_cpu_s"] - before["pg_cpu_s"], 3),
            "kafka_cpu_seconds": None if None in (before["kafka_cpu_s"], after["kafka_cpu_s"]) else
            round(after["kafka_cpu_s"] - before["kafka_cpu_s"], 3),
            "db": {k: after["db"][k] - before["db"][k] for k in before["db"]},
            "wall_seconds": round(after["wall"] - before["wall"], 3)}


def status_counts(conn, key_prefix=None):
    sql = "SELECT status, count(*) FROM notifications"
    params = ()
    if key_prefix:
        sql += " WHERE event_key LIKE %s"
        params = (key_prefix + "%",)
    counts = dict(conn.execute(sql + " GROUP BY status", params).fetchall())
    return {s: counts.get(s, 0) for s in ("PENDING", "PUBLISHED", "SENT", "SKIPPED", "DEAD")}


def wait_terminal(conn, deadline_s, key_prefix=None, sample_every=1.0, extra_done=None):
    """모든 행이 종결(SENT·SKIPPED·DEAD)될 때까지 대기. 초 단위 타임라인을 돌려준다."""
    start = time.perf_counter()
    timeline = []
    next_sample = 0.0
    while True:
        elapsed = time.perf_counter() - start
        counts = status_counts(conn, key_prefix)
        if elapsed >= next_sample:
            timeline.append({"t_s": round(elapsed, 2), **counts})
            next_sample += sample_every
        open_rows = counts["PENDING"] + counts["PUBLISHED"]
        if open_rows == 0 and (extra_done is None or extra_done()):
            return {"complete": True, "elapsed_s": round(elapsed, 3), "final": counts, "timeline": timeline}
        if elapsed > deadline_s:
            return {"complete": False, "elapsed_s": round(elapsed, 3), "final": counts, "timeline": timeline}
        time.sleep(0.25)


def e2e_latency(conn, key_prefix=None):
    sql = """SELECT extract(epoch FROM (sent_at - created_at)) * 1000 FROM notifications
             WHERE status = 'SENT'"""
    params = ()
    if key_prefix:
        sql += " AND event_key LIKE %s"
        params = (key_prefix + "%",)
    return stats_of([float(r[0]) for r in conn.execute(sql, params)])


def reconcile(conn, fcm, require_complete=True):
    """대차 검증 — 생성 = SENT + SKIPPED + DEAD, 멱등 키 중복 없음, SENT 는 전부 실제 발송됨."""
    counts = status_counts(conn)
    total = sum(counts.values())
    dup_keys = conn.execute("SELECT count(*) - count(DISTINCT (user_id, event_key)) FROM notifications").fetchone()[0]
    sent_ids = {r[0] for r in conn.execute("SELECT id FROM notifications WHERE status = 'SENT'")}
    ok_ids = set(fcm["ok_ids"])
    result = {"rows": total, "counts": counts, "duplicate_event_keys": int(dup_keys),
              "sent_not_delivered": len(sent_ids - ok_ids), "delivered_not_sent": len(ok_ids - sent_ids),
              "balanced": total == counts["SENT"] + counts["SKIPPED"] + counts["DEAD"]}
    assert dup_keys == 0, result
    assert not sent_ids - ok_ids, result
    if require_complete:
        assert result["balanced"], result
    return result


def open_loop(requester, count, rate, key_prefix, user_base, workers=16, cap=64):
    """고정 도착률 요청 — 응답 지연(latency, 스케줄 시각 기준)과 처리 시간(service)을 함께 기록."""
    rows, futures = [], []
    capacity = threading.BoundedSemaphore(cap)
    start = time.perf_counter()

    def task(due, index):
        began = time.perf_counter()
        try:
            error = requester.request(user_base + index, f"{key_prefix}:{index}")
        except Exception as exc:
            error = type(exc).__name__
        end = time.perf_counter()
        rows.append({"i": index, "scheduled_s": round(due - start, 4), "service_ms": (end - began) * 1000,
                     "latency_ms": (end - due) * 1000, "error": error})
        capacity.release()

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for i in range(count):
            due = start + i / rate
            time.sleep(max(0.0, due - time.perf_counter()))
            if capacity.acquire(blocking=False):
                futures.append(pool.submit(task, due, i))
            else:
                rows.append({"i": i, "scheduled_s": round(i / rate, 4), "error": "dropped_capacity"})
        for future in futures:
            future.result()
    rows.sort(key=lambda r: r["i"])
    ok = [r for r in rows if not r["error"]]
    return rows, {"offered": len(rows), "success": len(ok),
                  "errors": dict(Counter(r["error"] for r in rows if r["error"])),
                  "latency": stats_of([r["latency_ms"] for r in ok]),
                  "service": stats_of([r["service_ms"] for r in ok])}


def dump(out, name, value):
    (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n")


def dump_rows(out, name, rows):
    with gzip.open(out / name, "wt") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")


# ---------------------------------------------------------------------------------------------- 워크로드
def run_idle(conn, mode, out, seconds):
    truncate(conn)
    pipeline = Pipeline(mode, f"idle-{mode}")
    pipeline.start()
    time.sleep(3)
    before = resources(conn)
    time.sleep(seconds)
    after = resources(conn)
    pipeline.stop()
    result = {"mode": mode, "workload": "idle", "seconds": seconds, "resources": resource_delta(before, after),
              "pipeline": pipeline.stats()}
    dump(out, f"idle-{mode}.json", result)
    print(json.dumps({"idle": mode, **result["resources"]}), flush=True)
    return result


def run_w1(conn, mode, round_id, out, count, rate):
    truncate(conn)
    fcm_control(reset=True)
    pipeline = Pipeline(mode, f"w1-{mode}-{round_id}")
    pipeline.start()
    requester = Requester(mode)
    before = resources(conn)
    rows, requests = open_loop(requester, count, rate, "W1", 10001)
    drain = wait_terminal(conn, 300)
    after = resources(conn)
    fcm = fcm_stats(with_ids=True)
    pipeline.stop()
    check = reconcile(conn, fcm)
    raw = f"w1-{mode}-{round_id}.jsonl.gz"
    dump_rows(out, raw, rows)
    result = {"mode": mode, "workload": "w1", "round": round_id, "count": count, "rate": rate, "requests": requests,
              "e2e_delivery": e2e_latency(conn), "drain": {k: v for k, v in drain.items() if k != "timeline"},
              "fcm": {k: v for k, v in fcm.items() if k != "ok_ids"}, "reconcile": check,
              "resources": resource_delta(before, after), "pipeline": pipeline.stats(), "raw": raw}
    dump(out, f"w1-{mode}-{round_id}.json", result)
    print(json.dumps({"w1": mode, "round": round_id, "p95": requests["latency"]["p95_ms"],
                      "e2e_p95": result["e2e_delivery"]["p95_ms"], "errors": requests["errors"]}), flush=True)
    return result


def run_burst(conn, mode, round_id, out, workload, interference, outage_s, deadline_s):
    """W2(outage_s=0) / W3(outage_s>0). 버스트는 별도 스레드의 '요청'으로 낸다 — ①은 그 요청이 10,000건 발송을 끝내야 응답한다."""
    truncate(conn)
    fcm_control(reset=True)
    pipeline = Pipeline(mode, f"{workload}-{mode}-{round_id}")
    pipeline.start()
    requester = Requester(mode)
    burst_result = {}
    event_key = f"EVT:{OCCURRENCE_ID}:{workload}:{round_id}"

    def burst():
        burst_result.update(requester.event_start(event_key))

    before = resources(conn)
    t0 = time.perf_counter()
    burst_thread = threading.Thread(target=burst, daemon=True)
    burst_thread.start()
    outage = None
    if outage_s:
        time.sleep(interference["start_s"])
        fcm_control(outage_s=outage_s)
        outage = {"started_at_s": round(time.perf_counter() - t0, 3), "seconds": outage_s}
        outage_end_perf = time.perf_counter() + outage_s
    rows, requests = open_loop(requester, interference["count"], interference["rate"], f"{workload}-mix", 10001)
    interference_done_s = round(time.perf_counter() - t0, 3)
    drain = wait_terminal(conn, deadline_s, extra_done=lambda: not burst_thread.is_alive())
    all_terminal_at_s = round(time.perf_counter() - t0, 3)
    burst_thread.join(timeout=5)
    after = resources(conn)
    fcm = fcm_stats(with_ids=True)
    pipeline.stop()
    check = reconcile(conn, fcm, require_complete=drain["complete"])
    burst_counts = status_counts(conn, "EVT:")
    mix_counts = status_counts(conn, f"{workload}-mix:")
    burst_sent_at = conn.execute("""SELECT extract(epoch FROM (max(sent_at) - min(created_at)))
                                    FROM notifications WHERE event_key = %s""", (event_key,)).fetchone()[0]
    result = {"mode": mode, "workload": workload, "round": round_id, "burst": burst_result,
              "burst_rows": burst_counts, "burst_complete_s": round(float(burst_sent_at), 3) if burst_sent_at else None,
              "burst_wall_s": all_terminal_at_s, "complete": drain["complete"], "final": drain["final"],
              "interference": {**interference, "finished_at_s": interference_done_s, "rows": mix_counts, **requests},
              "outage": outage, "e2e_delivery_burst": e2e_latency(conn, "EVT:"),
              "fcm": {k: v for k, v in fcm.items() if k != "ok_ids"}, "reconcile": check,
              "resources": resource_delta(before, after), "pipeline": pipeline.stats(), "timeline": drain["timeline"]}
    if outage:
        lost = {k: v["DEAD"] + v["PENDING"] + v["PUBLISHED"] for k, v in (("burst", burst_counts), ("mix", mix_counts))}
        resume = None
        if fcm.get("first_ok_after_outage_wall") and fcm.get("outage_end_wall"):
            resume = round(fcm["first_ok_after_outage_wall"] - fcm["outage_end_wall"], 3)
        outage_end_at_s = outage["started_at_s"] + outage_s
        result["w3"] = {"lost": lost, "lost_total": sum(lost.values()),
                        "request_failures": requests["errors"].get("fcm_503", 0),
                        "inline_failures": burst_result.get("inline_failures"),
                        "outage_end_at_s": round(outage_end_at_s, 3), "all_terminal_at_s": all_terminal_at_s,
                        "finished_before_recovery": all_terminal_at_s < outage_end_at_s,
                        "recovery_to_all_terminal_s": round(max(0.0, all_terminal_at_s - outage_end_at_s), 3),
                        "first_success_after_recovery_s": resume,
                        "fcm_rejected": fcm["rejected"], "dead": drain["final"]["DEAD"],
                        "duplicates": fcm["duplicate_ids"]}
    dump(out, f"{workload}-{mode}-{round_id}.json", result)
    dump_rows(out, f"{workload}-{mode}-{round_id}.jsonl.gz", rows)
    summary_line = {workload: mode, "round": round_id, "complete_s": result["burst_complete_s"],
                    "wall_s": all_terminal_at_s, "fcm_calls": fcm["calls"], "dups": fcm["duplicate_ids"],
                    "mix_p95": requests["latency"]["p95_ms"], "mix_errors": requests["errors"]}
    if outage:
        summary_line["w3"] = result["w3"]
    print(json.dumps(summary_line), flush=True)
    return result


def run_w4(conn, mode, round_id, out, count=20):
    """FCM 성공 직후·SENT 기록 전 워커 사망 → 재기동. ①은 요청이 죽으므로 클라이언트가 같은 요청을 1회 재시도한다."""
    truncate(conn)
    fcm_control(reset=True)
    pipeline = Pipeline(mode, f"w4-{mode}-{round_id}")
    pipeline.start()
    requester = Requester(mode)
    crash["armed"] = True
    crash["fired"] = 0
    client_retries = 0
    request_errors = 0
    for i in range(count):
        try:
            if requester.request(10001 + i, f"W4:{i}"):
                request_errors += 1
        except CrashInjected:
            client_retries += 1
            if requester.request(10001 + i, f"W4:{i}"):
                request_errors += 1
    restarted = []
    if mode != "sync":
        deadline = time.perf_counter() + 60
        while crash["armed"] and time.perf_counter() < deadline:
            time.sleep(0.1)
        time.sleep(0.5)
        restarted = [w.name for w in pipeline.restart_crashed()]
    drain = wait_terminal(conn, 120)
    fcm = fcm_stats(with_ids=True)
    pipeline.stop()
    check = reconcile(conn, fcm)
    result = {"mode": mode, "workload": "w4", "round": round_id, "count": count, "crash_fired": crash["fired"],
              "client_retries": client_retries, "request_errors": request_errors, "restarted": restarted,
              "final": drain["final"], "fcm": {k: v for k, v in fcm.items() if k != "ok_ids"},
              "duplicates": fcm["duplicate_ids"], "reconcile": check, "pipeline": pipeline.stats()}
    crash["armed"] = False
    dump(out, f"w4-{mode}-{round_id}.json", result)
    print(json.dumps({"w4": mode, "round": round_id, "dups": fcm["duplicate_ids"], "final": drain["final"]}),
          flush=True)
    return result


# ---------------------------------------------------------------------------------------------- 요약
def median_of(values):
    present = [v for v in values if v is not None]
    return statistics.median(present) if present else None


def summarize(out, modes):
    summary = {"constants": CONST, "fcm_delay_ms": FCM_DELAY_MS, "modes": {}}
    for mode in modes:
        entry = {}
        idle = out / f"idle-{mode}.json"
        if idle.exists():
            data = json.loads(idle.read_text())
            entry["idle"] = {"seconds": data["seconds"], **data["resources"]}
        w1 = [json.loads(p.read_text()) for p in sorted(out.glob(f"w1-{mode}-*.json"))]
        if w1:
            entry["w1"] = {"rounds": len(w1),
                           "request_p50_ms": median_of(d["requests"]["latency"]["p50_ms"] for d in w1),
                           "request_p95_ms": median_of(d["requests"]["latency"]["p95_ms"] for d in w1),
                           "request_p99_ms": median_of(d["requests"]["latency"]["p99_ms"] for d in w1),
                           "service_p95_ms": median_of(d["requests"]["service"]["p95_ms"] for d in w1),
                           "errors": dict(sum((Counter(d["requests"]["errors"]) for d in w1), Counter())),
                           "e2e_p50_ms": median_of(d["e2e_delivery"]["p50_ms"] for d in w1),
                           "e2e_p95_ms": median_of(d["e2e_delivery"]["p95_ms"] for d in w1),
                           "e2e_p99_ms": median_of(d["e2e_delivery"]["p99_ms"] for d in w1),
                           "e2e_max_ms": median_of(d["e2e_delivery"]["max_ms"] for d in w1),
                           "pg_cpu_seconds": median_of(d["resources"]["pg_cpu_seconds"] for d in w1),
                           "kafka_cpu_seconds": median_of(d["resources"]["kafka_cpu_seconds"] for d in w1),
                           "xact_commit": median_of(d["resources"]["db"]["xact_commit"] for d in w1),
                           "per_round": [{"round": d["round"], "p50": d["requests"]["latency"]["p50_ms"],
                                          "p95": d["requests"]["latency"]["p95_ms"],
                                          "p99": d["requests"]["latency"]["p99_ms"],
                                          "e2e_p95": d["e2e_delivery"]["p95_ms"]} for d in w1]}
        for workload in ("w2", "w3"):
            runs = [json.loads(p.read_text()) for p in sorted(out.glob(f"{workload}-{mode}-*.json"))]
            if not runs:
                continue
            block = {"rounds": len(runs), "complete_all": all(d["complete"] for d in runs),
                     "burst_insert_ms": median_of(d["burst"].get("insert_ms") for d in runs),
                     "burst_request_ms": median_of(d["burst"].get("request_ms") for d in runs),
                     "burst_complete_s": median_of(d["burst_complete_s"] for d in runs),
                     "wall_to_all_terminal_s": median_of(d["burst_wall_s"] for d in runs),
                     "throughput_per_s": median_of(
                         (d["burst_rows"]["SENT"] / d["burst_complete_s"]) if d["burst_complete_s"] else None
                         for d in runs),
                     "fcm_calls": median_of(d["fcm"]["calls"] for d in runs),
                     "fcm_ok": median_of(d["fcm"]["ok"] for d in runs),
                     "duplicate_ids": median_of(d["fcm"]["duplicate_ids"] for d in runs),
                     "duplicate_ids_max": max(d["fcm"]["duplicate_ids"] for d in runs),
                     "interference_p50_ms": median_of(d["interference"]["latency"]["p50_ms"] for d in runs),
                     "interference_p95_ms": median_of(d["interference"]["latency"]["p95_ms"] for d in runs),
                     "interference_p99_ms": median_of(d["interference"]["latency"]["p99_ms"] for d in runs),
                     "interference_errors": dict(sum((Counter(d["interference"]["errors"]) for d in runs), Counter())),
                     "e2e_burst_p50_ms": median_of(d["e2e_delivery_burst"]["p50_ms"] for d in runs),
                     "e2e_burst_p95_ms": median_of(d["e2e_delivery_burst"]["p95_ms"] for d in runs),
                     "pg_cpu_seconds": median_of(d["resources"]["pg_cpu_seconds"] for d in runs),
                     "kafka_cpu_seconds": median_of(d["resources"]["kafka_cpu_seconds"] for d in runs),
                     "xact_commit": median_of(d["resources"]["db"]["xact_commit"] for d in runs),
                     "tup_updated": median_of(d["resources"]["db"]["tup_updated"] for d in runs),
                     "final_status_rounds": [d["final"] for d in runs],
                     "timelines": {str(d["round"]): d["timeline"] for d in runs}}
            if workload == "w3":
                block["w3"] = {"lost_total": [d["w3"]["lost_total"] for d in runs],
                               "lost_burst": [d["w3"]["lost"]["burst"] for d in runs],
                               "lost_mix": [d["w3"]["lost"]["mix"] for d in runs],
                               "request_failures": [d["w3"]["request_failures"] for d in runs],
                               "dead": [d["w3"]["dead"] for d in runs],
                               "fcm_rejected": [d["w3"]["fcm_rejected"] for d in runs],
                               "duplicates": [d["w3"]["duplicates"] for d in runs],
                               "recovery_to_all_terminal_s": [d["w3"]["recovery_to_all_terminal_s"] for d in runs],
                               "finished_before_recovery": [d["w3"]["finished_before_recovery"] for d in runs],
                               "all_terminal_at_s": [d["w3"]["all_terminal_at_s"] for d in runs],
                               "first_success_after_recovery_s": [d["w3"]["first_success_after_recovery_s"]
                                                                  for d in runs],
                               "outage": [d["outage"] for d in runs]}
            entry[workload] = block
        w4 = [json.loads(p.read_text()) for p in sorted(out.glob(f"w4-{mode}-*.json"))]
        if w4:
            entry["w4"] = {"rounds": len(w4), "duplicates": [d["duplicates"] for d in w4],
                           "crash_fired": [d["crash_fired"] for d in w4],
                           "client_retries": [d["client_retries"] for d in w4],
                           "final": [d["final"] for d in w4], "fcm_ok": [d["fcm"]["ok"] for d in w4],
                           "terminal_replay_blocked": [d["pipeline"].get("consumer", {}).get("terminal_replay_blocked", 0)
                                                       for d in w4]}
        summary["modes"][mode] = entry
    dump(out, "summary.json", summary)
    return summary


# ---------------------------------------------------------------------------------------------- main
def write_metadata(out, metadata):
    """실행을 여러 번 나눠 돌려도 metadata.json 하나에 runs 로 누적한다 (started_utc 가 실행 식별자)."""
    path = out / "metadata.json"
    existing = json.loads(path.read_text()) if path.exists() else {"runs": []}
    runs = [r for r in existing.get("runs", []) if r.get("started_utc") != metadata["started_utc"]]
    runs.append(metadata)
    dump(out, "metadata.json", {"runs": runs})


def order_for(modes, round_id):
    if round_id % 3 == 0:
        return list(modes)
    if round_id % 3 == 1:
        return list(reversed(modes))
    return modes[len(modes) // 2:] + modes[:len(modes) // 2]


def main():
    global SUBSCRIBERS
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=ROOT / "load-test/evidence/2026-09-30/notification-story")
    parser.add_argument("--modes", default="sync,db_poll,outbox_kafka")
    parser.add_argument("--workloads", default="idle,w1,w2,w3",
                        help="w4(크래시 창)는 선택 — 정본 검증은 NotificationCrashWindowTest 이고 여기선 대조용")
    parser.add_argument("--rounds", type=int, default=3, help="W1·W4 회차")
    parser.add_argument("--burst-rounds", type=int, default=1, help="W2·W3 회차 — 회차당 7~10분이라 기본 1")
    parser.add_argument("--w1-count", type=int, default=2000)
    parser.add_argument("--w1-rate", type=int, default=50)
    parser.add_argument("--burst-mix-seconds", type=int, default=10, help="W2 버스트 직후 W1 형 요청을 넣는 시간")
    parser.add_argument("--w3-mix-seconds", type=int, default=25, help="W3 에서 W1 형 요청을 넣는 시간(장애 창을 덮음)")
    parser.add_argument("--outage-start", type=int, default=5)
    parser.add_argument("--outage-seconds", type=int, default=20)
    parser.add_argument("--idle-seconds", type=int, default=30)
    parser.add_argument("--deadline", type=int, default=600, help="W2·W3 회차 상한(초) — 넘기면 complete=false 로 기록")
    parser.add_argument("--burst-size", type=int, default=SUBSCRIBERS, help="행사 구독자 수(= W2·W3 버스트 크기)")
    parser.add_argument("--summarize-only", action="store_true")
    args = parser.parse_args()
    SUBSCRIBERS = args.burst_size
    args.out.mkdir(parents=True, exist_ok=True)
    modes = args.modes.split(",")
    if args.summarize_only:
        summarize(args.out, modes)
        return
    workloads = args.workloads.split(",")
    stub = multiprocessing.get_context("spawn").Process(target=serve_stub, args=(FCM_PORT, FCM_DELAY_MS), daemon=True)
    stub.start()
    for _ in range(50):
        try:
            fcm_stats()
            break
        except OSError:
            time.sleep(0.1)
    metadata = {"platform": platform.platform(), "python": platform.python_version(),
                "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "started_utc": datetime.now(timezone.utc).isoformat(), "constants": CONST,
                "backoff_ms": BACKOFF_MS, "fcm_delay_ms": FCM_DELAY_MS,
                "db_poll": {"interval_ms": DB_POLL_INTERVAL_MS, "batch": DB_POLL_BATCH, "claim_sql": CLAIM_BATCH},
                "users": USERS, "subscribers": SUBSCRIBERS, "args": {k: str(v) for k, v in vars(args).items()},
                "containers": {name: subprocess.check_output(
                    ["docker", "inspect", "-f", "{{.Config.Image}} cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}}",
                     name], text=True).strip() for name in (PG_CONTAINER, KAFKA_CONTAINER)},
                "libraries": {"psycopg": psycopg.__version__,
                              "confluent_kafka": __import__("confluent_kafka").version()[0],
                              "librdkafka": __import__("confluent_kafka").libversion()[0]},
                "source_sha256": {f: hashlib.sha256((ROOT / f).read_bytes()).hexdigest() for f in SOURCES.values()}}
    write_metadata(args.out, metadata)
    with pg() as conn:
        setup_schema(conn)
        time.sleep(3)   # pg_stat 반영 대기 — 첫 idle 측정에 적재 통계가 섞이지 않게
        metadata["server_version"] = conn.execute("SHOW server_version").fetchone()[0]
        write_metadata(args.out, metadata)
        if "idle" in workloads:
            for mode in modes:
                run_idle(conn, mode, args.out, args.idle_seconds)
        if "w1" in workloads:
            for round_id in range(args.rounds):
                for mode in order_for(modes, round_id):
                    run_w1(conn, mode, round_id, args.out, args.w1_count, args.w1_rate)
        if "w2" in workloads:
            for round_id in range(args.burst_rounds):
                for mode in order_for(modes, round_id):
                    run_burst(conn, mode, round_id, args.out, "w2",
                              {"start_s": 0, "count": args.w1_rate * args.burst_mix_seconds, "rate": args.w1_rate},
                              0, args.deadline)
        if "w3" in workloads:
            for round_id in range(args.burst_rounds):
                for mode in order_for(modes, round_id):
                    run_burst(conn, mode, round_id, args.out, "w3",
                              {"start_s": args.outage_start, "count": args.w1_rate * args.w3_mix_seconds,
                               "rate": args.w1_rate}, args.outage_seconds, args.deadline)
        if "w4" in workloads:
            for round_id in range(args.rounds):
                for mode in order_for(modes, round_id):
                    run_w4(conn, mode, round_id, args.out)
    summarize(args.out, modes)
    metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
    write_metadata(args.out, metadata)


if __name__ == "__main__":
    main()
