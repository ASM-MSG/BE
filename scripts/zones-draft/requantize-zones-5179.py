# MSG-347: 확정 시드(seed/zones.json)의 zone 사각형을 EPSG:5179 격자 인덱스로 재양자화한다.
# 구 인덱스는 위경도 등간격이라 정확히 역산되므로, 검수 결정(합집합·경계 조정·수동 작도 31건)을
# 그대로 보존한 채 격자 번호만 새 체계로 다시 매길 수 있다 — 원천 공공데이터 재수집이 필요 없다.
#
# 절차: 구 인덱스 → 위경도 사각형(min = 인덱스 × 구 STEP, max = (인덱스+1) × 구 STEP)
#       → 꼭짓점 4점을 EPSG:5179 로 변환 → 사각형의 네 변을 가장 가까운 새 셀 경계로 스냅한다.
#       상한 변은 배타라 그 경계 인덱스에서 1을 빼야 마지막 칸이 된다.
#
# 스펙 초안은 min/max 를 그대로 floor 하는 절차였는데, 그러면 상한 변이 걸친 칸까지 세어 모든 zone 이
# 변마다 한 칸씩 커지고 맞닿아 있던 이웃 zone 과 겹친다(실측: 48건 중 7쌍 겹침 — validate-zones.py FAIL,
# "겹치지 않게 관리" 원칙 위반). 변을 가장 가까운 경계로 스냅하면 맞닿은 두 zone 의 공유 변이 같은 경계로
# 가 겹침이 생기지 않는다(실측: 겹침 0건, 크기 증감 0~+1칸). 검수 결정을 보존한다는 목적에 이쪽이 맞다.
#
# 좌표계 정의는 GridConstants.CRS_DEF_EPSG5179 와 글자 단위로 같은 계약 문자열을 pyproj 에 직접 넘긴다
# (pyproj 내장 EPSG:5179 정의에 의존하지 않는다 — 서버·FE·마이그레이션과 정의가 어긋날 여지를 없앤다).
#
# ⚠️ 1회성 스크립트 — 재실행 금지. 시드를 제자리 갱신하므로 두 번 돌리면 이미 새 체계인 인덱스를
#    구 인덱스로 재해석해 엉뚱한 사각형이 된다 (되돌리려면 git 으로 zones.json 을 복원한 뒤 1회만 실행).
#
# 사용법: pip install -r requirements.txt && python3 scripts/zones-draft/requantize-zones-5179.py
#         → src/main/resources/seed/zones.json 을 제자리 갱신하고 검수는 validate-zones.py 로 한다.
import json
import math
import pathlib

from pyproj import CRS, Transformer

GRID_LAT_STEP = 0.0009    # 구 격자 상수 (MSG-347 전) — 역산 전용
GRID_LNG_STEP = 0.00115
CELL_SIZE_METERS = 100
CRS_DEF_EPSG5179 = ("+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 "
                    "+ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs")
ROW_CAP = 25              # zones CHECK: max_grid_y - min_grid_y <= 25 (알파벳 A..Z 26행)

ROOT = pathlib.Path(__file__).resolve().parents[2]
SEED = ROOT / "src/main/resources/seed/zones.json"

to_meters = Transformer.from_crs("EPSG:4326", CRS.from_proj4(CRS_DEF_EPSG5179), always_xy=True)


def requantize(zone):
    south = zone["minGridY"] * GRID_LAT_STEP
    north = (zone["maxGridY"] + 1) * GRID_LAT_STEP
    west = zone["minGridX"] * GRID_LNG_STEP
    east = (zone["maxGridX"] + 1) * GRID_LNG_STEP

    corners = [to_meters.transform(lon, lat)
               for lat in (south, north) for lon in (west, east)]
    xs = [x for x, _ in corners]
    ys = [y for _, y in corners]

    zone["minGridY"] = snap(min(ys))
    zone["maxGridY"] = snap(max(ys)) - 1
    zone["minGridX"] = snap(min(xs))
    zone["maxGridX"] = snap(max(xs)) - 1
    return zone


def snap(meters):
    """미터 좌표를 가장 가까운 셀 경계의 인덱스로 스냅한다."""
    return math.floor(meters / CELL_SIZE_METERS + 0.5)


zones = json.loads(SEED.read_text(encoding="utf-8"))
before = {z["zoneKey"]: (z["maxGridY"] - z["minGridY"] + 1, z["maxGridX"] - z["minGridX"] + 1) for z in zones}
zones = [requantize(z) for z in zones]

over_cap = [z["zoneKey"] for z in zones if z["maxGridY"] - z["minGridY"] > ROW_CAP]
if over_cap:
    raise SystemExit(f"26행 캡 초과 — 사각형 축소 결정이 필요하다: {over_cap}")

SEED.write_text(json.dumps(zones, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
for z in zones:
    rows, cols = z["maxGridY"] - z["minGridY"] + 1, z["maxGridX"] - z["minGridX"] + 1
    print(f"{z['zoneKey']:20s} {before[z['zoneKey']][0]:2d}×{before[z['zoneKey']][1]:2d} → {rows:2d}×{cols:2d}"
          f"  y {z['minGridY']}..{z['maxGridY']}  x {z['minGridX']}..{z['maxGridX']}")
print(f"{SEED.relative_to(ROOT)} — zone {len(zones)} 건 재양자화")
