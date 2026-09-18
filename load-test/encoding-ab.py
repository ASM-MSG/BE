"""Dev 전용 한/두 노드 비교. 표본은 실제 영상, 결과는 로컬 JSON. 종료 시 워커 복구.

실행: python3 load-test/encoding-ab.py INPUT.mp4 RESULT.json
외부 변경: dev 테스트 계정/비공개 영상 생성, AI EC2 인코딩 워커 stop/start.
"""
import concurrent.futures
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import urllib.request

BASE = 'https://api-dev.fillmap.kr'
KEY = str(pathlib.Path('~/.ssh/fillmap-key-soma.pem').expanduser())
BE = 'ubuntu@52.79.187.34'
AI = 'ubuntu@52.78.158.240'


def remote(host, command):
    return subprocess.check_output(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8',
                                    '-i', KEY, host, command], text=True, timeout=60).strip()


def sql(query):
    # SQL is only composed from this script's constants and validated integer IDs.
    return subprocess.check_output(['ssh', '-o', 'BatchMode=yes', '-i', KEY, BE,
                                   'docker exec -i fillmap-postgres-dev psql -v ON_ERROR_STOP=1 '
                                   '-U dev -d fillmap -At'], input=query, text=True, timeout=30).strip()


def request(path, token=None, data=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    body = json.dumps(data).encode() if data is not None else None
    with urllib.request.urlopen(urllib.request.Request(BASE + path, body, headers, method=method),
                                timeout=60) as response:
        return json.load(response).get('data')


def jobs(ids):
    assert ids and all(type(i) is int for i in ids)
    return json.loads(sql('SELECT coalesce(json_agg(t),\'[]\') FROM ('
        'SELECT v.id,v.processing_status,j.status,j.claimed_by,j.attempt_count,'
        'extract(epoch from (j.completed_at-j.enqueued_at))*1000 AS job_total_ms '
        'FROM videos v JOIN video_encoding_jobs j ON j.video_id=v.id '
        'AND j.original_s3_key=v.original_s3_key WHERE v.id IN (' + ','.join(map(str, ids)) + ')) t;'))


def ready_worker():
    remote(AI, 'sudo docker start fillmap-encoding-worker >/dev/null')
    for _ in range(60):
        if remote(AI, "sudo docker inspect --format '{{.State.Health.Status}}' fillmap-encoding-worker") == 'healthy':
            return
        time.sleep(2)
    raise RuntimeError('worker health timeout')


def main():
    source, destination = map(pathlib.Path, sys.argv[1:])
    assert source.is_file() and not destination.exists()
    destination.parent.mkdir(parents=True, exist_ok=True)
    run = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S')
    result = {'run': run, 'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'rounds': [], 'video_ids': [], 'errors': []}

    def save():
        destination.write_text(json.dumps(result, ensure_ascii=False, indent=2))

    assert remote(AI, "sudo docker inspect --format '{{.State.Running}}' fillmap-encoding-worker") == 'true'
    assert sql("SELECT count(*) FROM video_encoding_jobs WHERE status IN ('PENDING','PROCESSING');") == '0'
    result['images'] = [remote(BE, "docker inspect --format '{{.Config.Image}}' fillmap-api"),
                        remote(AI, "sudo docker inspect --format '{{.Config.Image}}' fillmap-encoding-worker")]
    assert result['images'][0] == result['images'][1], 'node image mismatch'
    token = request('/api/auth/dev/social-login', data={'provider': 'KAKAO', 'oid': 'encoding-ab-' + run})['accessToken']
    result['test_oid'] = 'encoding-ab-' + run
    samples = []
    for duration in (10, 19, 29):
        sample = destination.parent / f'{run}-{duration}s.mp4'
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(source), '-t', str(duration),
                        '-c', 'copy', '-y', str(sample)], check=True)
        samples.append((sample, duration))
    result['samples'] = [{'duration': d, 'bytes': p.stat().st_size,
                          'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p, d in samples]
    save()

    def upload(index):
        sample, duration = samples[index % 3]
        presign = request('/api/videos/presigned-url', token, {'extension': 'mp4',
            'contentType': 'video/mp4', 'contentLength': sample.stat().st_size, 'purpose': 'UPLOAD'})
        with urllib.request.urlopen(urllib.request.Request(presign['uploadUrl'], sample.read_bytes(),
             {'Content-Type': 'video/mp4'}, method='PUT'), timeout=120) as response:
            assert response.status == 200
        start = time.monotonic()
        video = request('/api/videos', token, {'s3Key': presign['s3Key'], 'lat': 37.5665,
            'lng': 126.978, 'durationSec': duration, 'visibility': 'PRIVATE',
            'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()})
        return {'id': int(video['videoId']), 'duration': duration, 'confirmed_mono': time.monotonic(),
                'confirm_ms': (time.monotonic() - start) * 1000}

    try:
        # ABBA reduces ordering bias; cold starts remain included in the raw samples.
        for nodes in (1, 2, 2, 1):
            if nodes == 1:
                remote(AI, 'sudo docker stop -t 45 fillmap-encoding-worker >/dev/null')
            else:
                ready_worker()
            for count in (3, 6):
                start = time.monotonic()
                with concurrent.futures.ThreadPoolExecutor(max_workers=count) as pool:
                    futures = [pool.submit(upload, i) for i in range(count)]
                    uploads = []
                    for future in futures:
                        item = future.result()
                        uploads.append(item)
                        result['video_ids'].append(item['id'])
                        save()
                ids = [item['id'] for item in uploads]
                terminal = {}
                while len(terminal) < count:
                    rows = jobs(ids)
                    for row in rows:
                        if row['processing_status'] in ('READY', 'FAILED') and row['id'] not in terminal:
                            terminal[row['id']] = (row, time.monotonic())
                    if time.monotonic() - start > 480:
                        raise RuntimeError('round timeout: ' + str(rows))
                    time.sleep(0.5)
                for item in uploads:
                    row, ended = terminal[item['id']]
                    item['ready_ms'] = (ended - item.pop('confirmed_mono')) * 1000
                    item.update(row)
                result['rounds'].append({'nodes': nodes, 'concurrency': count,
                    'wall_seconds': time.monotonic() - start, 'items': uploads})
                save()
                print(json.dumps(result['rounds'][-1]), flush=True)
                assert all(i['processing_status'] == 'READY' and i['status'] == 'COMPLETED' for i in uploads)
                time.sleep(5)
    except BaseException as error:
        result['errors'].append(type(error).__name__ + ': ' + str(error)[:200])
        raise
    finally:
        try:
            ready_worker()
            result['worker_restored'] = True
        finally:
            save()
    # Only our dedicated account: regular deletion handles DB cascades and S3 cleanup.
    request('/api/users/me', token, method='DELETE')
    result['test_account_deleted'] = True
    save()


if __name__ == '__main__':
    main()
