"""Dev 워커 종료 시험. 강제 종료 시험은 자기 작업의 임대만 만료시켜 35분 대기를 압축합니다.

python3 load-test/encoding-recovery.py VIDEO_19S.mp4 RESULT.json
"""
import concurrent.futures
import datetime
import json
import pathlib
import runpy
import sys
import time


def main():
    helpers = runpy.run_path(str(pathlib.Path(__file__).with_name('encoding-ab.py')))
    remote, sql, request, jobs, ready = [helpers[n] for n in ('remote', 'sql', 'request', 'jobs', 'ready_worker')]
    ai = helpers['AI']
    sample, output = map(pathlib.Path, sys.argv[1:])
    assert not output.exists()
    assert sql("SELECT count(*) FROM video_encoding_jobs WHERE status IN ('PENDING','PROCESSING');") == '0'
    policy = remote(ai, "sudo docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' fillmap-encoding-worker")
    assert policy == 'unless-stopped'
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S')
    token = request('/api/auth/dev/social-login', data={'provider': 'KAKAO', 'oid': 'encoding-fault-' + stamp})['accessToken']
    result = {'run': stamp, 'faults': [], 'ids': [], 'lease_note': 'KILL: own test row lease forced expired after crash; not production recovery SLA'}

    def save():
        output.write_text(json.dumps(result, indent=2))

    def upload(_):
        p = request('/api/videos/presigned-url', token, {'extension': 'mp4', 'contentType': 'video/mp4',
            'contentLength': sample.stat().st_size, 'purpose': 'UPLOAD'})
        import urllib.request
        with urllib.request.urlopen(urllib.request.Request(p['uploadUrl'], sample.read_bytes(),
             {'Content-Type': 'video/mp4'}, method='PUT'), timeout=120) as response:
            assert response.status == 200
        v = request('/api/videos', token, {'s3Key': p['s3Key'], 'lat': 37.5665, 'lng': 126.978,
            'durationSec': 19, 'visibility': 'PRIVATE',
            'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()})
        return int(v['videoId'])

    try:
        for mode in ('TERM', 'KILL'):
            ready()
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                ids = list(pool.map(upload, range(3)))
            result['ids'].extend(ids)
            save()
            deadline = time.monotonic() + 60
            target = None
            while time.monotonic() < deadline:
                target = next((r for r in jobs(ids) if r['status'] == 'PROCESSING' and r['claimed_by'] == 'ai'), None)
                if target:
                    break
                time.sleep(.3)
            assert target, 'AI claim not observed; no signal sent'
            if mode == 'KILL':
                remote(ai, 'sudo docker update --restart=no fillmap-encoding-worker >/dev/null')
            start = time.monotonic()
            command = ('sudo docker stop -t 45' if mode == 'TERM' else 'sudo docker kill --signal KILL')
            remote(ai, command + ' fillmap-encoding-worker >/dev/null')
            immediate = jobs([target['id']])[0]
            if mode == 'KILL':
                assert immediate['status'] == 'PROCESSING' and immediate['claimed_by'] == 'ai'
                # Only the killed test job; this validates reclaim, not the configured 35-minute wait.
                sql("UPDATE video_encoding_jobs SET lease_until=(statement_timestamp() AT TIME ZONE 'utc') "
                    "- interval '1 second' WHERE video_id=" + str(target['id']) + " AND status='PROCESSING' AND claimed_by='ai';")
            deadline = time.monotonic() + 240
            while True:
                rows = jobs(ids)
                if len(rows) == 3 and all(r['processing_status'] in ('READY', 'FAILED') for r in rows):
                    break
                assert time.monotonic() < deadline, 'recovery timeout'
                time.sleep(.5)
            assert all(r['processing_status'] == 'READY' and r['status'] == 'COMPLETED' for r in rows)
            recovered = next(r for r in rows if r['id'] == target['id'])
            assert recovered['claimed_by'] == 'be'
            assert recovered['attempt_count'] == (target['attempt_count'] if mode == 'TERM' else target['attempt_count'] + 1)
            result['faults'].append({'mode': mode, 'target_before': target, 'immediate': immediate,
                                    'rows': rows, 'recovery_ms': (time.monotonic() - start) * 1000})
            save()
            print(json.dumps(result['faults'][-1]), flush=True)
            remote(ai, 'sudo docker update --restart=unless-stopped fillmap-encoding-worker >/dev/null')
    finally:
        remote(ai, 'sudo docker update --restart=unless-stopped fillmap-encoding-worker >/dev/null')
        ready()
        result['worker_restored'] = True
        save()
    request('/api/users/me', token, method='DELETE')
    result['test_account_deleted'] = True
    save()


if __name__ == '__main__':
    main()
