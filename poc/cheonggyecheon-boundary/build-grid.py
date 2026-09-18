"""Regenerate the comparison grid with the BE's actual EPSG:5179 definition.

Uses the existing scripts/requirements.txt dependency (pyproj==3.6.1).
"""
import json
import math
import re
from pathlib import Path

from pyproj import Transformer

root = Path(__file__).resolve().parent
constants = (root.parents[1] / "src/main/java/com/msg/fillmap/grid/GridConstants.java").read_text()
declaration = constants.split("CRS_DEF_EPSG5179 =", 1)[1].split(";", 1)[0]
crs = "".join(re.findall(r'"([^"]+)"', declaration))
cell_size = int(re.search(r"CELL_SIZE_METERS = (\d+)", constants).group(1))
forward = Transformer.from_crs("EPSG:4326", crs, always_xy=True)
inverse = Transformer.from_crs(crs, "EPSG:4326", always_xy=True)
source = json.loads((root / "centerline.geojson").read_text())
points = [forward.transform(*point) for point in source["geometry"]["coordinates"]]
west, south = [math.floor((min(p[i] for p in points) - 500) / cell_size) * cell_size for i in (0, 1)]
east, north = [math.ceil((max(p[i] for p in points) + 500) / cell_size) * cell_size for i in (0, 1)]

def to_lonlat(x, y):
    return [round(value, 9) for value in inverse.transform(x, y)]

lines = [[to_lonlat(x, y) for y in range(south, north + 1, cell_size)]
         for x in range(west, east + 1, cell_size)]
lines += [[to_lonlat(x, y) for x in range(west, east + 1, cell_size)]
          for y in range(south, north + 1, cell_size)]
# Self-check the generated vertices against the 100m lattice after coordinate rounding.
max_error = max(abs(value - round(value / cell_size) * cell_size)
                for line in lines for point in line for value in forward.transform(*point))
assert max_error < 0.001, f"격자 꼭짓점이 100m 눈금에서 벗어남: {max_error}m"
grid = {
    "type": "Feature",
    "properties": {"crs_definition": crs, "cell_size_m": cell_size,
                   "description": "GridConstants.java 기준 100m 격자. 청계천 주변 500m까지 생성."},
    "geometry": {"type": "MultiLineString", "coordinates": lines},
}
(root / "grid.geojson").write_text(json.dumps(grid, ensure_ascii=False, separators=(",", ":")) + "\n")
print(f"PASS: {len(lines)} grid lines, {cell_size}m spacing, max round-trip error {max_error:.6f}m")
