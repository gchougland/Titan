/**
 * Node harness for Titan Rig pure helpers (no Blockbench).
 *
 * Usage: node harness.mjs
 * Run from tools/blockbench/ or with cwd anywhere; resolves the Titan repo root from this file.
 */
import { createRequire } from 'module';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const Core = require('./core.cjs');
const repoRoot = path.resolve(__dirname, '..', '..');

let failed = 0;
function assert(condition, message) {
	if (!condition) {
		failed++;
		console.error('FAIL:', message);
	} else {
		console.log('ok:', message);
	}
}

function read(rel) {
	return fs.readFileSync(path.join(repoRoot, rel), 'utf8');
}

// --- buildBoneTable ---
{
	const doc = JSON.parse(read('src/main/resources/Server/Titan/Skeletons/Yaga_Egg.json'));
	const table = Core.buildBoneTable(doc);
	assert(table.bones.length === 1, 'Yaga_Egg has one bone');
	assert(table.bones[0].name === 'Shell', 'Yaga_Egg root is Shell');
	assert(table.bones[0].origin[0] === 0 && table.bones[0].origin[1] === 0, 'Shell origin at offset');
}

{
	const doc = JSON.parse(read('src/main/resources/Server/Titan/Skeletons/Roaming_Temple.json'));
	const table = Core.buildBoneTable(doc);
	assert(table.byName.has('Pelvis'), 'Temple has Pelvis');
	assert(table.byName.has('FL-Foot'), 'Temple has FL-Foot');
	const pelvis = table.byName.get('Pelvis');
	assert(Math.abs(pelvis.origin[1] - 23) < 1e-6, 'Pelvis origin Y is hip height 23');
	const foot = table.byName.get('FL-Foot');
	const calf = table.byName.get('FL-Calf');
	assert(foot.parent === 'FL-Calf', 'FL-Foot parents to FL-Calf');
	assert(Math.abs(foot.origin[1] - (calf.origin[1] + foot.offset[1])) < 1e-6, 'Foot origin = calf + offset');
	assert(foot.ikRole === 'Foot', 'FL-Foot tagged as IK Foot');
}

// --- validateBoneTree ---
{
	const ok = Core.validateBoneTree([
		{ Name: 'A' },
		{ Name: 'B', Parent: 'A' }
	]);
	assert(ok.length === 0, 'valid tree has no errors');

	const dup = Core.validateBoneTree([{ Name: 'A' }, { Name: 'A' }]);
	assert(dup.some(e => /Duplicate/.test(e)), 'detects duplicate names');

	const cycle = Core.validateBoneTree([
		{ Name: 'A', Parent: 'B' },
		{ Name: 'B', Parent: 'A' }
	]);
	assert(cycle.some(e => /cycle/.test(e)), 'detects parent cycle');

	const missing = Core.validateBoneTree([{ Name: 'A', Parent: 'Nope' }]);
	assert(missing.some(e => /missing parent/.test(e)), 'detects missing parent');
}

// --- emitNewSkeleton / emitVariantStub ---
{
	const sk = Core.emitNewSkeleton({ id: 'Harness_Test', rootBone: 'Root', hipHeight: 5 });
	const parsed = JSON.parse(sk.text);
	assert(parsed.Bones[0].Name === 'Root', 'new skeleton root bone');
	assert(parsed.HipHeight === 5, 'new skeleton hip height');
	assert(Array.isArray(parsed.IkChains), 'new skeleton has IkChains');

	const errors = Core.validateBoneTree(parsed.Bones);
	assert(errors.length === 0, 'emitted skeleton validates');

	const variant = Core.emitVariantStub({
		id: 'Harness_Test',
		skeleton: 'Harness_Test',
		displayName: 'Harness Test',
		weakpointCountMax: 2
	});
	const vdoc = JSON.parse(variant.text);
	assert(vdoc.Skeleton === 'Harness_Test', 'variant points at skeleton');
	assert(vdoc.WeakpointCountMax === 2, 'variant weakpoint count');
	assert(!('ShellHealth' in vdoc), 'omits zero shell health');
}

// --- assembleSkeletonDoc ---
{
	const assembled = Core.assembleSkeletonDoc({
		bodyBone: 'Root',
		unitScale: 1,
		hipHeight: 4,
		bones: [
			{ name: 'Root', parent: null, offset: [0, 4, 0], rotation: [0, 0, 0], prefab: null },
			{ name: 'Arm', parent: 'Root', offset: [2, 0, 0], rotation: [0, 0, 0], prefab: 'Titan/Yaga/Yaga_Egg_Shell', hollow: true }
		],
		sockets: [{ Bone: 'Arm', Offset: { X: 0, Y: 1, Z: 0 } }],
		ikChains: []
	});
	assert(assembled.Bones.length === 2, 'assemble writes two bones');
	assert(assembled.Bones[1].Prefab === 'Titan/Yaga/Yaga_Egg_Shell', 'assemble keeps prefab');
	assert(assembled.Bones[1].Hollow === true, 'assemble keeps hollow');
	assert(assembled.WeakpointSockets.length === 1, 'assemble keeps sockets');
}

// --- JsonDocument splice against a real skeleton ---
{
	const raw = read('src/main/resources/Server/Titan/Skeletons/Yaga_Egg.json');
	const doc = new Core.JsonDocument(raw);
	assert(doc.value.Bones[0].Name === 'Shell', 'JsonDocument parses Bones');

	const changed = Core.setVector(doc, '/Bones/0', 'Offset', [1, 2, 3], true);
	assert(changed, 'setVector reports a change');
	const text = doc.apply();
	const roundTrip = JSON.parse(text);
	assert(roundTrip.Bones[0].Offset.X === 1, 'spliced Offset.X');
	assert(roundTrip.Bones[0].Offset.Y === 2, 'spliced Offset.Y');
	assert(roundTrip.Bones[0].Offset.Z === 3, 'spliced Offset.Z');
	assert(text.indexOf('$Comment') >= 0, 'splice preserves $Comment');
}

{
	const raw = read('src/main/resources/Server/Titan/Skeletons/Roaming_Temple.json');
	const doc = new Core.JsonDocument(raw);
	Core.setNumber(doc, '/Bones/0', 'Scale', 1.5, 1);
	const text = doc.apply();
	assert(JSON.parse(text).Bones[0].Scale === 1.5, 'Temple Scale splice');
	assert(/\n\t"\$Comment"/.test(text) || text.indexOf('$Comment') >= 0, 'Temple comments survive');
}

// --- prefab attach fields round-trip through assemble ---
{
	const meta = {
		name: 'Leg',
		parent: 'Root',
		offset: [0, -5, 0],
		rotation: [0, 90, 0],
		prefab: 'Titan/Temple/Temple_Leg',
		prefabYaw: 90,
		pivot: [1, 25, 1],
		scale: 1,
		sliceMinY: 15,
		sliceMaxY: 24,
		hollow: true,
		colliderStride: 1,
		colliderAllFaces: true
	};
	const def = Core.boneDefFromMeta(meta, meta.name, meta.parent);
	assert(def.PrefabYaw === 90, 'boneDef PrefabYaw');
	assert(def.SliceMinY === 15 && def.SliceMaxY === 24, 'boneDef slices');
	assert(def.Pivot.Y === 25, 'boneDef pivot');
}

if (failed) {
	console.error('\n' + failed + ' assertion(s) failed');
	process.exit(1);
}
console.log('\nAll harness checks passed.');
