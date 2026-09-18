import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {boundaryFor} from './geometry.mjs';

const turf = createRequire(import.meta.url)('./vendor/turf.min.js');
const line = JSON.parse(readFileSync(new URL('./centerline.geojson', import.meta.url)));
const coordinates = line.geometry.coordinates;
const grid = JSON.parse(readFileSync(new URL('./grid.geojson', import.meta.url)));
const constants = readFileSync(new URL('../../src/main/java/com/msg/fillmap/grid/GridConstants.java', import.meta.url), 'utf8');
const crs = [...constants.split('CRS_DEF_EPSG5179 =')[1].split(';')[0].matchAll(/"([^"]+)"/g)]
	.map((match) => match[1]).join('');
assert.equal(grid.properties.crs_definition, crs, 'BE 좌표계 계약과 비교 격자가 일치');
assert.equal(grid.properties.cell_size_m, Number(constants.match(/CELL_SIZE_METERS = (\d+)/)[1]));
assert.equal(grid.geometry.type, 'MultiLineString');
assert.equal(line.geometry.type, 'LineString');
assert.equal(coordinates.length, 87);
assert.deepEqual(line.properties.osm_way_ids, [368276771, 769631455]);
assert.deepEqual(coordinates[0], [126.9785975, 37.5691438]);
assert.deepEqual(coordinates.at(-1), [127.0506623, 37.5527466]);
assert.equal(coordinates.filter(([lng, lat]) => lng === 127.0093071 && lat === 37.5697923).length, 1);
for (const [lng, lat] of coordinates) {
	assert(lng > 126.97 && lng < 127.06 && lat > 37.55 && lat < 37.58, '경도·위도 순서와 청계천 범위');
}

let previousArea = 0;
for (let radius = 5; radius <= 80; radius += 5) {
	const boundary = boundaryFor(line, radius, turf);
	assert(turf.booleanValid(boundary), `${radius}m 경계의 기하 유효성`);
	assert.equal(turf.kinks(boundary).features.length, 0, `${radius}m 굽은 부분의 자기 교차 없음`);
	assert(turf.area(boundary) > previousArea, '폭이 넓어지면 면적이 증가');
	previousArea = turf.area(boundary);
	// Check the whole route at 25m intervals, including the join and both endpoints.
	for (let meters = 0; meters < turf.length(line, {units: 'meters'}); meters += 25) {
		assert(turf.booleanPointInPolygon(turf.along(line, meters, {units: 'meters'}), boundary));
	}
	assert(turf.booleanPointInPolygon(coordinates.at(-1), boundary));
	// A normal to a real straight segment verifies ground-meter widths, not screen pixels or degrees.
	const a = coordinates[8], b = coordinates[9];
	const midpoint = turf.midpoint(a, b);
	const normal = turf.bearing(a, b) + 90;
	assert(turf.booleanPointInPolygon(turf.destination(midpoint, radius * .8, normal, {units: 'meters'}), boundary));
	assert(!turf.booleanPointInPolygon(turf.destination(midpoint, radius * 1.2, normal, {units: 'meters'}), boundary));
	const exported = JSON.parse(JSON.stringify(boundary));
	assert.equal(exported.properties.half_width_m, radius);
	assert.equal(exported.properties.license, 'ODbL-1.0');
}
for (const radius of [NaN, Infinity, -20, 0, 4, 81, '20', null]) {
	assert.throws(() => boundaryFor(line, radius, turf), RangeError);
}
console.log(`PASS: 원본 87좌표, 폭 16종, 25m 간격 중심선 포함, 안팎 판정, 자기 교차, 입력 검증`);
console.log(`중심선 ${turf.length(line).toFixed(3)}km / 편측 20m 면적 ${(turf.area(boundaryFor(line, 20, turf)) / 10000).toFixed(2)}ha`);
