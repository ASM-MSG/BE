/** Same polygon is rendered and used for point checks. Radius is in ground meters. */
export function boundaryFor(centerline, radius, turf) {
	if (!Number.isFinite(radius) || radius < 5 || radius > 80) {
		throw new RangeError('경계 폭은 편측 5~80m로 지정합니다.');
	}
	const boundary = turf.buffer(centerline, radius, {units: 'meters', steps: 16});
	if (!boundary || !['Polygon', 'MultiPolygon'].includes(boundary.geometry.type)) {
		throw new Error('경계 도형을 만들지 못했습니다.');
	}
	boundary.properties = {
		...centerline.properties,
		name: '청계천 실험 경계',
		half_width_m: radius,
		description: 'OSM 수로 중심선에서 만든 일정 폭의 실험 경계입니다. 공식 하천·산책로 경계가 아닙니다.'
	};
	return boundary;
}
