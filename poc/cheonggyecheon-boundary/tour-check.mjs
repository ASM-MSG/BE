// Standalone feasibility experiment. Entry times, exits, stops and accessibility are SYNTHETIC.
import assert from 'node:assert/strict';
import {readFileSync, writeFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {performance} from 'node:perf_hooks';
const turf = createRequire(import.meta.url)('./vendor/turf.min.js');
const line = JSON.parse(readFileSync(new URL('centerline.geojson', import.meta.url)));
const length = turf.length(line, {units: 'meters'});
const nodes = Array.from({length: 21}, (_, i) => turf.along(line, length*i/20, {units: 'meters'}));
const segments = Array.from({length: 20}, (_, i) => {
 const geometry = turf.lineSliceAlong(line, length*i/20, length*(i+1)/20, {units: 'meters'});
 return {id: i, from: i, to: i+1, geometry,
  walkMin: turf.length(geometry, {units: 'meters'})/70, stopMin: 2,
  score: 1 + i%4, open: true, accessible: true};
});
const exits = Array.from({length:11},(_,i)=>({node:i*2, minutes:2, accessible:true}));
function entriesFor(origin) {
 return exits.map(exit => ({...exit, minutes: 2 + Math.abs(exit.node-origin)*length/20/70}));
}

function recommend(segments, entries, exits, budget, accessible = false) {
 if (![30,60,120].includes(budget) || typeof accessible !== 'boolean') throw Error('invalid request');
 for (const [i,s] of segments.entries()) {
  if (s.id !== i || s.from !== i || s.to !== i+1 ||
   ![s.walkMin,s.stopMin,s.score].every(v => Number.isFinite(v) && v>=0) || s.walkMin===0 ||
   typeof s.open !== 'boolean' || ![true,false,null].includes(s.accessible)) throw Error('invalid segment');
 }
 for (const port of [...entries,...exits]) {
  if (!Number.isInteger(port.node) || port.node<0 || port.node>segments.length ||
   !Number.isFinite(port.minutes) || port.minutes<0 ||
   ![true,false,null].includes(port.accessible)) throw Error('invalid port');
 }
 let best = null;
 // ponytail: enumerate both directions on one linear chain; use a graph when branches are required.
 for (const entry of entries) {
  if (accessible && entry.accessible !== true) continue;
  for (const direction of [-1,1]) {
   let node=entry.node, minutes=entry.minutes, score=0;
   const selected=[];
   while (node+direction>=0 && node+direction<=segments.length) {
    const s=segments[direction===1 ? node : node-1];
    if (!s.open || (accessible && s.accessible !== true)) break;
    selected.push(s.id);
    minutes+=s.walkMin+s.stopMin;
    score+=s.score;
    node+=direction;
    if (minutes>budget) break;
    for (const exit of exits.filter(e => e.node===node && (!accessible || e.accessible===true))) {
     const total=minutes+exit.minutes;
     if (total>budget) continue;
     if (!best || score>best.score || (score===best.score && total<best.minutes)) {
      best={entry:entry.node, exit:node, direction, segments:[...selected], score, minutes:total,
       entryMinutes:entry.minutes, exitMinutes:exit.minutes};
     }
    }
   }
  }
 }
 return best;
}

function verify(route, model, budget, accessible=false) {
 assert.ok(route, 'a feasible fixture route must exist');
 assert.ok(route.segments.length>0);
 let position=route.entry;
 let total=route.entryMinutes+route.exitMinutes;
 for (const id of route.segments) {
  const s=model[id];
  assert.equal(position, route.direction===1 ? s.from : s.to);
  position=route.direction===1 ? s.to : s.from;
  assert.ok(s.open);
  if (accessible) assert.equal(s.accessible,true);
  total+=s.walkMin+s.stopMin;
 }
 assert.equal(position,route.exit);
 assert.ok(total<=budget);
 assert.ok(Math.abs(route.minutes-total)<1e-9);
}
for (const s of segments) {
 const coords=s.geometry.geometry.coordinates;
 assert.ok(turf.distance(turf.point(coords[0]),nodes[s.from],{units:'meters'})<0.01);
 assert.ok(turf.distance(turf.point(coords.at(-1)),nodes[s.to],{units:'meters'})<0.01);
}
const cases=[];
const sparseExits=exits.filter(exit=>exit.node%4===0);
// With only six exits a four-segment journey plus entry/stops/exit exceeds 30 minutes.
assert.equal(recommend(segments,[{node:0,minutes:2,accessible:true}],sparseExits,30),null);
for (const origin of [0,12]) for (const budget of [30,60,120]) {
 const route=recommend(segments,entriesFor(origin),exits,budget);
 verify(route,segments,budget);
 assert.deepEqual(route,recommend(segments,entriesFor(origin),exits,budget));
 cases.push({origin,budget,...route});
}
assert.ok(cases.some(c=>c.direction===-1), 'exercise reverse travel');
const oneEntry=[{node:0,minutes:2,accessible:true}];
const closed=structuredClone(segments);
closed[5].open=false;
const stopped=recommend(closed,oneEntry,exits,120);
verify(stopped,closed,120);
assert.equal(stopped.exit,4); // cannot skip blocked segment 5 to reach exit 8
const unknown=structuredClone(segments);
unknown[5].accessible=null;
const wheelchair=recommend(unknown,oneEntry,exits,120,true);
verify(wheelchair,unknown,120,true);
assert.equal(wheelchair.exit,4);
unknown[5].accessible=false;
assert.equal(recommend(unknown,oneEntry,exits,120,true).exit,4);
assert.equal(recommend(segments,oneEntry,[],120),null);
assert.equal(recommend(segments,[{...oneEntry[0],minutes:121}],exits,120),null);
assert.equal(recommend(segments,[{...oneEntry[0],accessible:null}],exits,120,true),null);
assert.throws(()=>recommend(segments,oneEntry,exits,NaN));
assert.throws(()=>recommend(segments,[{...oneEntry[0],minutes:-1}],exits,120));
const disconnected=structuredClone(segments); disconnected[5].from=7;
assert.throws(()=>recommend(disconnected,oneEntry,exits,120));

// Spot placement: known along-line positions are recovered, far-away points need rejection.
const spotOffsets=[0.1,0.35,0.7].map(fraction=> {
 const point=turf.along(line,length*fraction,{units:'meters'});
 const snap=turf.nearestPointOnLine(line,point,{units:'meters'});
 assert.ok(Math.abs(snap.properties.location-length*fraction)<0.1);
 return {fraction,offsetM:snap.properties.location,distanceM:snap.properties.dist};
});
const far=turf.destination(nodes[0],500,0,{units:'meters'});
assert.ok(turf.nearestPointOnLine(line,far,{units:'meters'}).properties.dist>40);

// Fixture-only GPS debounce: 3 fresh confirmations, reject inaccurate/out-of-order samples.
function transitions(samples) {
 let inside=false, pending=null, count=0, lastTime=-Infinity;
 const events=[];
 for (const s of samples) {
  if (!Number.isFinite(s.time) || !Number.isFinite(s.accuracy) || s.accuracy<0 ||
   s.accuracy>30 || s.time<=lastTime || typeof s.inside!=='boolean') {
   pending=null; count=0; continue;
  }
  if (s.time-lastTime>5) {pending=null;count=0;}
  lastTime=s.time;
  if (s.inside===inside) {pending=null;count=0;continue;}
  count=pending===s.inside ? count+1 : 1; pending=s.inside;
  if (count===3) {inside=s.inside;events.push({time:s.time,inside});count=0;pending=null;}
 }
 return events;
}
const sample=(time,inside,accuracy=5)=>({time,inside,accuracy});
assert.equal(transitions(Array.from({length:40},(_,i)=>sample(i,i%2===0))).length,0);
assert.deepEqual(transitions([sample(1,true),sample(2,true),sample(3,true),
 sample(4,false),sample(5,false),sample(6,false)]),[{time:3,inside:true},{time:6,inside:false}]);
assert.equal(transitions([sample(1,true),sample(2,true,90),sample(3,true)]).length,0);
assert.equal(transitions([sample(1,true),sample(1,true),sample(2,true)]).length,0);
assert.equal(transitions([sample(1,true),sample(20,true),sample(21,true)]).length,0);

const timings=[];
for(let i=0;i<1100;i++) {
 const t=performance.now();
 recommend(segments,entriesFor(12),exits,120);
 if(i>=100) timings.push(performance.now()-t);
}
timings.sort((a,b)=>a-b);
const result={fixture:'OSM waterway geometry; synthetic 20 segments, 11 entries/exits, 70m/min, 2min stops',
 cases,closedSegment:stopped,unknownAccessibility:wheelchair,spotOffsets,
 checks:{sixBudgets:true,continuity:true,closedCannotJump:true,unknownAccessibilityDenied:true,
  noExitReturnsNone:true,sparseExits30minImpossible:true,entryTimeCounted:true,invalidInputRejected:true,spotOffset:true,gpsDebounceFixtures:true},
 timing:{node:process.version,samples:timings.length,p50_ms:timings[499],p95_ms:timings[949],max_ms:timings.at(-1),
  scope:'in-process function, no HTTP/DB/AI/map provider, not API p95'}};
writeFileSync(new URL('tour-results.json',import.meta.url),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result,null,2));
console.log('PASS: 6 time/origin cases + discontinuity, unknown accessibility, spot and GPS fixtures');
