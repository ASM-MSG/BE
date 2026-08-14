# MSG-394 D5: courses-seed.json 의 스팟 격자 인덱스를 EPSG:5179 체계로 재양자화한다.
#
# 왜 재수집이 아니라 재양자화인가: DB 가 정본이고 파일이 따라간다(2026-08-14 성민 결정). V28 이
# mission_grids 를 새 체계로 옮길 때 mission_grids 에는 원본 좌표가 없어서 구 셀의 중심점을 원본으로
# 삼아 재인코딩했다. 같은 산술을 파일에 적용하면 DB 를 읽지 않고도 같은 값이 나오고, 그래서 대조에
# 허용 오차가 없다 (989 대 989 전수 일치). 스팟의 실제 좌표에서 다시 인코딩하면 상당수가 어긋나
# "정상 편차"와 결함을 구별할 수 없다.
#
#   구 셀 중심  lat = (gridY + 0.5) x 0.0009 ,  lon = (gridX + 0.5) x 0.00115
#   새 인덱스    (lat, lon) -> EPSG:5179 -> floor(y / 100), floor(x / 100)
#
# 좌표계 정의는 GridConstants.CRS_DEF_EPSG5179 와 글자 단위로 같은 계약 문자열을 pyproj 에 직접
# 넘긴다. pyproj 내장 EPSG:5179 정의를 쓰면 서버·FE·마이그레이션과 어긋날 여지가 생긴다.
# 문자열이 맞는지는 눈으로 보지 않고 픽스처 200 건 재계산으로 확인한 뒤에야 본 작업을 시작한다.
#
# ⚠️ 1회성 스크립트 — 재실행 금지. 파일을 제자리 갱신하므로 두 번 돌리면 이미 새 체계인 인덱스를
#    구 인덱스로 재해석해 엉뚱한 셀이 된다. 되돌릴 백업이 없다(courses-seed.json 은 레포 밖이라
#    버전 관리가 안 되고, D6 대로 파이프라인 전량 재실행으로도 이 결과는 재현되지 않는다).
#    그래서 임시 파일에 쓰고 검증한 뒤 원자 교체한다.
#
# 사용법: pip install -r scripts/requirements.txt
#         python3 scripts/requantize-courses-5179.py ~/fillmap-data/durunubi/courses-seed.json
import json
import math
import os
import pathlib
import sys

from pyproj import CRS, Transformer

GRID_LAT_STEP = 0.0009     # 구 격자 상수 (MSG-347 전) — 역산 전용
GRID_LNG_STEP = 0.00115
CELL_SIZE_METERS = 100
DENSIFY_M = 20             # spot_pipeline.py 와 같은 값 — 통과 격자 집합 산출용

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "src/test/resources/fixtures/grid-epsg5179-samples.json"

CRS_DEF_EPSG5179 = ("+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 "
                    "+ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs")

to_meters = Transformer.from_crs("EPSG:4326", CRS.from_proj4(CRS_DEF_EPSG5179), always_xy=True)


def grid_id(lat, lon):
	x, y = to_meters.transform(lon, lat)
	return f"{math.floor(y / CELL_SIZE_METERS)}_{math.floor(x / CELL_SIZE_METERS)}"


def self_check():
	"""픽스처 200 건으로 계약 문자열과 인코딩을 검증한다. 어긋나면 아무것도 쓰지 않고 멈춘다."""
	fixture = json.loads(FIXTURE.read_text())
	if fixture["crsDefinition"] != CRS_DEF_EPSG5179:
		sys.exit(f"좌표계 계약 문자열이 픽스처와 다릅니다\n  스크립트: {CRS_DEF_EPSG5179}\n"
		         f"  픽스처  : {fixture['crsDefinition']}")
	if fixture["cellSizeMeters"] != CELL_SIZE_METERS:
		sys.exit(f"셀 크기가 픽스처와 다릅니다: {fixture['cellSizeMeters']} != {CELL_SIZE_METERS}")
	bad = [s for s in fixture["samples"] if grid_id(s["lat"], s["lon"]) != s["gridId"]]
	if bad:
		s = bad[0]
		sys.exit(f"픽스처 재계산 불일치 {len(bad)}/{len(fixture['samples'])} 건. "
		         f"예: ({s['lat']}, {s['lon']}) 기대 {s['gridId']} 실제 {grid_id(s['lat'], s['lon'])}")
	print(f"자가검증 통과 — 계약 문자열 일치, 픽스처 {len(fixture['samples'])} 건 재계산 일치")


def old_cell_center(old_grid_id):
	"""구 인덱스 -> 구 셀 중심 위경도 (V28 6단계와 같은 산술)."""
	gy, gx = old_grid_id.split("_")
	return (int(gy) + 0.5) * GRID_LAT_STEP, (int(gx) + 0.5) * GRID_LNG_STEP


def is_old_format(course):
	"""구 체계 판별 — 재실행 사고를 값으로 잡는다. 구 gridY 는 33000~40000 대다."""
	return all(int(s["gridId"].split("_")[0]) > 30000 for s in course["spots"])


def path_grid_ids(path):
	"""코스 경로가 지나는 새 체계 격자 집합. 20m 이하 간격으로 보간해 모은다."""
	pts = [(lat, lon) for lon, lat in path["coordinates"]]
	cells = set()
	for (lat1, lon1), (lat2, lon2) in zip(pts, pts[1:]):
		coslat = math.cos(math.radians(lat1))
		span_m = math.hypot((lat2 - lat1) * 111320, (lon2 - lon1) * 111320 * coslat)
		steps = max(1, math.ceil(span_m / DENSIFY_M))
		for i in range(steps + 1):
			t = i / steps
			cells.add(grid_id(lat1 + (lat2 - lat1) * t, lon1 + (lon2 - lon1) * t))
	return cells


def main():
	if len(sys.argv) != 2:
		sys.exit("사용법: python3 scripts/requantize-courses-5179.py <courses-seed.json 경로>")
	seed = pathlib.Path(sys.argv[1]).expanduser()
	self_check()

	courses = json.loads(seed.read_text())
	stale = [c["name"] for c in courses if not is_old_format(c)]
	if stale:
		sys.exit(f"이미 새 체계인 코스가 있습니다 ({len(stale)} 건, 예: {stale[0]}). "
		         "이 스크립트는 1회성입니다 — 두 번 돌리면 안 됩니다.")

	spot_count = 0
	off_path = 0
	for course in courses:
		on_path = path_grid_ids(course["path"])
		seen = set()
		for spot in course["spots"]:
			new_id = grid_id(*old_cell_center(spot["gridId"]))
			if new_id in seen:
				sys.exit(f"새 체계에서 격자가 중복됩니다: {course['name']} seq {spot['seq']} = {new_id}. "
				         "리더가 거부하므로 그대로 쓸 수 없습니다.")
			seen.add(new_id)
			spot["gridId"] = new_id
			spot_count += 1
			if new_id not in on_path:
				off_path += 1

	print(f"코스 {len(courses)} 개, 스팟 {spot_count} 개, 코스 내 격자 중복 0 건")
	print(f"경로 밖 스팟 {off_path} 개 ({off_path / spot_count * 100:.1f}%) "
	      "— 구 셀 중심이 원본이라 생기는 편차다 (스펙 §미해결 질문 1번)")

	tmp = seed.with_suffix(seed.suffix + ".tmp")
	tmp.write_text(json.dumps(courses, ensure_ascii=False, indent=1))
	check = json.loads(tmp.read_text())
	if len(check) != len(courses) or sum(len(c["spots"]) for c in check) != spot_count:
		sys.exit(f"임시 파일 검증 실패 — 교체하지 않았습니다: {tmp}")
	os.replace(tmp, seed)
	print(f"원자 교체 완료: {seed}")


if __name__ == "__main__":
	main()
