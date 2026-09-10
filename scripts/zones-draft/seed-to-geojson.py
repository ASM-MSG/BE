# 확정 시드(seed/zones.json)를 geojson.io 검수용 GeoJSON 으로 변환한다 (MSG-259).
# build-candidate.py 와 달리 공공데이터 원본이 필요 없다 — 레포에 커밋된 파일만 읽으므로 누구나 돌릴 수 있다.
# 사용법: python3 scripts/zones-draft/seed-to-geojson.py   →   zones-seed.geojson (커밋 대상 아님)
import json
import pathlib

from pyproj import CRS, Transformer

CELL_SIZE_METERS = 100    # GridConstants 공유 상수 (MSG-347 — 5179 미터 평면에서 셀 한 변)
CRS_DEF_EPSG5179 = ("+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 "
                    "+ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs")

ROOT = pathlib.Path(__file__).resolve().parents[2]
SEED = ROOT / "src/main/resources/seed/zones.json"
MANUAL = ROOT / "scripts/zones-draft/zones-manual.json"
OUT = ROOT / "scripts/zones-draft/zones-seed.geojson"

zones = json.loads(SEED.read_text(encoding="utf-8"))
# 출처는 시드에 없다 — 수동 작도분 원본에 있는 zoneKey 로 되살린다 (나머지는 공공데이터 유래).
manual = {z["zoneKey"]: z.get("_sources", ["수동 작도 (FR-2a)"])
          for z in json.loads(MANUAL.read_text(encoding="utf-8"))}


to_degrees = Transformer.from_crs(CRS.from_proj4(CRS_DEF_EPSG5179), "EPSG:4326", always_xy=True)


def polygon(z):
	# 격자 인덱스는 셀의 남서 모서리 → 사각형 동/북단은 max+1 칸까지 덮는다.
	# 사각형을 5179 미터 평면에서 잡고 꼭짓점 4점을 위경도로 되돌린다 — 자오선 수렴만큼 기울어져 보인다.
	west, east = z["minGridX"] * CELL_SIZE_METERS, (z["maxGridX"] + 1) * CELL_SIZE_METERS
	south, north = z["minGridY"] * CELL_SIZE_METERS, (z["maxGridY"] + 1) * CELL_SIZE_METERS
	ring = [(west, south), (east, south), (east, north), (west, north), (west, south)]
	return [[list(to_degrees.transform(x, y)) for x, y in ring]]


features = [{
	"type": "Feature",
	"properties": {
		"name": z["name"],
		"zoneKey": z["zoneKey"],
		"rows": z["maxGridY"] - z["minGridY"] + 1,
		"cols": z["maxGridX"] - z["minGridX"] + 1,
		"sources": manual.get(z["zoneKey"], ["공공데이터"]),
	},
	"geometry": {"type": "Polygon", "coordinates": polygon(z)},
} for z in zones]

OUT.write_text(json.dumps({"type": "FeatureCollection", "features": features},
                          ensure_ascii=False, indent=1), encoding="utf-8")
print(f"{OUT.relative_to(ROOT)} — zone {len(features)} 건")
