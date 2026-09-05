/*
 * Titan Rig - a Blockbench companion plugin for authoring titan animations.
 *
 * A titan has no model file. It is assembled at runtime from prefab voxels parented to the bones in
 * Server/Titan/Skeletons/<id>.json, so there is nothing for Blockbench to open. This plugin builds the
 * equivalent scene: one group per bone, one cube per prefab voxel, textured from the game's own block
 * art, and the skeleton's clip set loaded as editable animations.
 *
 * It deliberately implements no .blockyanim exporter. The official Hytale plugin already owns that
 * round-trip and patches Animation.prototype.save, so setting animation.path here is enough for Ctrl+S
 * to write a clip straight back into the repo.
 *
 * Requires: Blockbench desktop, and the official "Hytale Models" plugin for the hytale_character format.
 */
(function () {
'use strict';

const PLUGIN_ID = 'titan_rig';

/** Keyframe times in a .blockyanim are frame indices at this rate, matching BlockyAnimParser. */
const FPS = 60;

const HYTALE_FORMATS = ['hytale_character', 'hytale_prop'];

/** Where the mod keeps each kind of asset, relative to the repo root. */
const REPO = {
	skeletons: 'src/main/resources/Server/Titan/Skeletons',
	variants: 'src/main/resources/Server/Titan/Variants',
	clips: 'src/main/resources/Server/Titan/Clips',
	prefabs: 'src/main/resources/Server/Prefabs',
	models: 'src/main/resources/Server/Models',
	common: 'src/main/resources/Common'
};

/**
 * Hytale names block faces by world axis and so does Blockbench, so the two map straight across.
 * The protocol's front/back/left/right aliases resolve to the same axes (front = south = +Z).
 */
const FACES = ['up', 'down', 'north', 'south', 'east', 'west'];

const tracked = [];
function track(item) { tracked.push(item); return item; }

// Action.delete() does not take the action out of the menus it was added to, so reloading the plugin
// during development would stack a second copy of every entry. Placements are undone by hand.
const placements = [];
function placeIn(menu, action, path) {
	menu.addAction(action, path);
	placements.push({ menu: menu, action: action });
	return action;
}

let fs = null;
let nodePath = null;

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function joinPath() {
	return nodePath.join.apply(nodePath, Array.prototype.slice.call(arguments));
}

/** Asset JSON in both the mod and the game ships with a BOM often enough to be worth always stripping. */
function parseJson(text, label) {
	try {
		return JSON.parse(String(text).replace(/^\uFEFF/, ''));
	} catch (err) {
		throw new Error('Invalid JSON in ' + (label || 'file') + ': ' + err.message);
	}
}

function readJsonFile(path) {
	return parseJson(fs.readFileSync(path, 'utf8'), path);
}

function num(value, fallback) {
	return typeof value === 'number' && isFinite(value) ? value : fallback;
}

function vec(source, fallback) {
	const f = fallback || 0;
	if (!source) return [f, f, f];
	return [num(source.X, f), num(source.Y, f), num(source.Z, f)];
}

function fileExists(path) {
	try { return fs.existsSync(path); } catch (err) { return false; }
}

function listJsonNames(dir) {
	if (!fileExists(dir)) return [];
	return fs.readdirSync(dir)
		.filter(name => /\.json$/i.test(name))
		.map(name => name.replace(/\.json$/i, ''))
		.sort();
}

function warn(message) {
	console.warn('[Titan Rig] ' + message);
}

function fail(title, message) {
	Blockbench.showMessageBox({ title: title, message: message, buttons: ['OK'] });
}

// ---------------------------------------------------------------------------
// Math
//
// The two sides disagree on Euler order and this is the only place that reconciles them. Skeleton JSON
// rotations are XYZ-order degrees because TitanPose.resetToBind feeds them to JOML's rotateXYZ, while
// Blockbench stores group rotation as ZYX. Everything converts through a quaternion so the two never
// have to agree directly.
// ---------------------------------------------------------------------------

function quatFromAxis(angleDeg, ax, ay, az) {
	const h = (angleDeg * Math.PI / 180) * 0.5;
	const s = Math.sin(h);
	return [s * ax, s * ay, s * az, Math.cos(h)];
}

function quatMul(a, b) {
	return [
		a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
		a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
		a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
		a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
	];
}

/** XYZ-order Euler degrees to a quaternion, matching TitanPose.resetToBind. */
function eulerXyzToQuat(x, y, z) {
	return quatMul(quatMul(quatFromAxis(x, 1, 0, 0), quatFromAxis(y, 0, 1, 0)), quatFromAxis(z, 0, 0, 1));
}

function quatToEuler(q, order) {
	const t = new THREE.Quaternion(q[0], q[1], q[2], q[3]).normalize();
	const e = new THREE.Euler().setFromQuaternion(t, order);
	return [
		Math.roundTo(Math.radToDeg(e.x), 4),
		Math.roundTo(Math.radToDeg(e.y), 4),
		Math.roundTo(Math.radToDeg(e.z), 4)
	];
}

function eulerToQuat(euler, order) {
	const e = new THREE.Euler(Math.degToRad(euler[0]), Math.degToRad(euler[1]), Math.degToRad(euler[2]), order);
	const q = new THREE.Quaternion().setFromEuler(e);
	return [q.x, q.y, q.z, q.w];
}

/** Skeleton bind rotation (XYZ degrees) to the ZYX Euler degrees Blockbench keeps on a group. */
function bindRotationToGroup(euler) {
	if (!euler[0] && !euler[1] && !euler[2]) return [0, 0, 0];
	return quatToEuler(eulerXyzToQuat(euler[0], euler[1], euler[2]), 'ZYX');
}

/** The inverse, for writing a dragged group back into the skeleton. */
function groupRotationToBind(rotation) {
	if (!rotation[0] && !rotation[1] && !rotation[2]) return [0, 0, 0];
	return quatToEuler(eulerToQuat(rotation, 'ZYX'), 'XYZ');
}

/**
 * TitanSpawner.spawnWeakpoints aims the ore's growth axis, its local +Y, along the socket's outward
 * direction. The marker carries that same turn, so which way it leans in Blockbench is which way the
 * ore will grow in game.
 */
function normalToMarkerRotation(normal) {
	const target = new THREE.Vector3(normal[0], normal[1], normal[2]);
	if (target.lengthSq() < 1e-12) return [0, 0, 0];
	const q = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 1, 0), target.normalize());
	return quatToEuler([q.x, q.y, q.z, q.w], 'ZYX');
}

/** The inverse: the direction a rotated marker points. */
function markerRotationToNormal(rotation) {
	const q = eulerToQuat(rotation, 'ZYX');
	const v = new THREE.Vector3(0, 1, 0).applyQuaternion(new THREE.Quaternion(q[0], q[1], q[2], q[3]));
	return [v.x, v.y, v.z];
}

/** The normal the runtime derives when a socket does not declare one: straight out from the bone pivot. */
function derivedNormal(offset) {
	const v = new THREE.Vector3(offset[0], offset[1], offset[2]);
	if (v.lengthSq() < 1e-12) return null;
	v.normalize();
	return [v.x, v.y, v.z];
}

/**
 * Stable per-voxel randomness. Block types carry weighted texture variants and the game picks one per
 * placement; hashing the coordinate keeps the pick varied but identical across reimports, so a rig does
 * not visibly reshuffle every time it is reloaded.
 */
function hash3(x, y, z) {
	let h = (Math.imul(x | 0, 73856093) ^ Math.imul(y | 0, 19349663) ^ Math.imul(z | 0, 83492791)) >>> 0;
	h ^= h >>> 15;
	h = Math.imul(h, 0x2c1b3c6d) >>> 0;
	h ^= h >>> 12;
	h = Math.imul(h, 0x297a2d39) >>> 0;
	h ^= h >>> 15;
	return (h >>> 0) / 4294967296;
}

// ---------------------------------------------------------------------------
// Asset source
//
// Game assets come either as the shipped Assets.zip or as an extracted tree (the shared-source repo
// keeps one under HytaleAssets/). Both expose Common/ and Server/ at the top, so a tiny interface over
// the two lets everything downstream ignore which it got.
// ---------------------------------------------------------------------------

function FolderAssetSource(root) {
	this.root = root;
	this.kind = 'folder';
}

FolderAssetSource.prototype.has = function (rel) {
	return fileExists(joinPath(this.root, rel));
};

FolderAssetSource.prototype.read = function (rel) {
	const path = joinPath(this.root, rel);
	return fileExists(path) ? fs.readFileSync(path) : null;
};

FolderAssetSource.prototype.list = function (prefix, pattern) {
	const out = [];
	const start = joinPath(this.root, prefix);
	if (!fileExists(start)) return out;
	const stack = [start];
	while (stack.length) {
		const dir = stack.pop();
		let items;
		try { items = fs.readdirSync(dir, { withFileTypes: true }); } catch (err) { continue; }
		for (const item of items) {
			const full = joinPath(dir, item.name);
			if (item.isDirectory()) {
				stack.push(full);
			} else if (!pattern || pattern.test(item.name)) {
				out.push(nodePath.relative(this.root, full).replace(/\\/g, '/'));
			}
		}
	}
	return out;
};

FolderAssetSource.prototype.close = function () { /* nothing to release */ };

/** Common/ holds the block textures and Server/ the item definitions; both mark a real asset root. */
function isAssetRoot(dir) {
	return fileExists(joinPath(dir, 'Common')) && fileExists(joinPath(dir, 'Server'));
}

const EXTRACT_HINT =
	'Assets.zip has to be extracted first. Blockbench only lets a plugin read whole files, not seek ' +
	'inside one, and the archive is about 3.5 GB, so it cannot be opened in place. This is the same ' +
	'reason the Hytale Avatar Loader asks for an extracted folder.\n\n' +
	'Extract Assets.zip once, then set the Hytale asset root to the folder that contains Common/ and ' +
	'Server/.';

/**
 * Takes whatever the user pointed at and finds the folder that actually holds Common/ and Server/,
 * looking a couple of levels in either direction so picking a parent or a checkout root both work.
 */
function openAssetSource(configured) {
	if (!configured) throw new Error('No Hytale asset root configured.');

	if (/\.zip$/i.test(configured)) throw new Error(EXTRACT_HINT);
	if (!fileExists(configured)) throw new Error('Asset root does not exist:\n' + configured);
	if (fs.statSync(configured).isFile()) throw new Error('The Hytale asset root must be a folder, not a file:\n' + configured);

	const candidates = [
		configured,
		joinPath(configured, 'HytaleAssets'),
		joinPath(configured, 'Assets'),
		joinPath(configured, 'hytale-shared-source', 'HytaleAssets'),
		joinPath(configured, '..', 'Assets'),
		joinPath(configured, '..', '..', 'Assets')
	];
	for (const candidate of candidates) {
		if (isAssetRoot(candidate)) return new FolderAssetSource(nodePath.resolve(candidate));
	}

	if (fileExists(joinPath(configured, 'Assets.zip'))) throw new Error(EXTRACT_HINT);

	throw new Error(
		'No Hytale assets under:\n' + configured +
		'\n\nExpected a folder containing both Common/ and Server/, for example the HytaleAssets folder ' +
		'of a shared-source checkout, or the folder you extracted Assets.zip into.'
	);
}

// ---------------------------------------------------------------------------
// Block types
//
// There is no block registry. A block id is an item id, and the block definition is nested inside
// Server/Item/Items/**/<id>.json, optionally inheriting from another item via Parent.
// ---------------------------------------------------------------------------

function BlockTypeIndex(source) {
	this.source = source;
	this.byId = new Map();
	this.raw = new Map();
	this.resolved = new Map();
	this.descriptions = new Map();

	for (const rel of source.list('Server/Item/Items', /\.json$/i)) {
		const file = rel.slice(rel.lastIndexOf('/') + 1);
		this.byId.set(file.replace(/\.json$/i, ''), rel);
	}
}

/**
 * Child wins, and arrays replace rather than concatenate: Rock_Basalt inherits Rock_Stone purely to
 * swap its Textures array, so merging the two would leave it wearing both.
 */
function mergeInherited(parent, child) {
	if (Array.isArray(child) || child === null || typeof child !== 'object') return child;
	if (Array.isArray(parent) || parent === null || typeof parent !== 'object') return child;
	const out = Object.assign({}, parent);
	for (const key of Object.keys(child)) {
		out[key] = key in parent ? mergeInherited(parent[key], child[key]) : child[key];
	}
	return out;
}

BlockTypeIndex.prototype.fetch = function (id) {
	if (this.raw.has(id)) return this.raw.get(id);
	const rel = this.byId.get(id);
	let item = null;
	if (rel) {
		const buffer = this.source.read(rel);
		if (buffer) {
			try { item = parseJson(buffer.toString('utf8'), rel); } catch (err) { warn(err.message); }
		}
	}
	this.raw.set(id, item);
	return item;
};

/** Merges an item with its Parent chain. */
BlockTypeIndex.prototype.item = function (id, seen) {
	if (this.resolved.has(id)) return this.resolved.get(id);

	let item = this.fetch(id);
	if (item && item.Parent) {
		const chain = seen || new Set();
		if (chain.has(id)) {
			warn('Circular Parent chain at block "' + id + '"');
		} else {
			chain.add(id);
			const parent = this.item(String(item.Parent), chain);
			if (parent) item = mergeInherited(parent, item);
		}
	}

	this.resolved.set(id, item);
	return item;
};

function parseTint(tint) {
	const hex = Array.isArray(tint) ? tint[0] : tint;
	if (typeof hex !== 'string') return null;
	const match = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
	if (!match) return null;
	const value = parseInt(match[1], 16);
	return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

/**
 * Flattens one Textures entry into six faces. Textures is either a single object or a weighted array,
 * and each entry mixes the shorthand keys with per-face overrides, most specific last.
 */
function expandFaces(entry) {
	const faces = {};
	const set = (keys, value) => { if (value) for (const key of keys) faces[key] = value; };
	set(FACES, entry.All);
	set(['north', 'south', 'east', 'west'], entry.Sides);
	set(['up', 'down'], entry.UpDown);
	set(['up'], entry.Up);
	set(['down'], entry.Down);
	set(['north'], entry.North);
	set(['south'], entry.South);
	set(['east'], entry.East);
	set(['west'], entry.West);
	return faces;
}

const TINT_FACE_KEYS = { up: 'TintUp', down: 'TintDown', north: 'TintNorth', south: 'TintSouth', east: 'TintEast', west: 'TintWest' };

/**
 * Everything the renderer needs for one block id: a list of weighted variants, each mapping the six
 * faces to either a texture path or a flat colour, plus the tint to bake in.
 */
BlockTypeIndex.prototype.describe = function (id) {
	if (this.descriptions.has(id)) return this.descriptions.get(id);
	const result = this.buildDescription(id);
	this.descriptions.set(id, result);
	return result;
};

BlockTypeIndex.prototype.buildDescription = function (id) {
	const item = this.item(id);
	const block = item && item.BlockType;
	if (!block) return null;
	if (block.DrawType === 'Empty') return null;

	const drawType = block.DrawType || 'Cube';
	const raw = block.Textures;
	const entries = Array.isArray(raw) ? raw : (raw ? [raw] : []);
	const baseTint = parseTint(block.Tint);

	const variants = entries.map(entry => {
		const paths = expandFaces(entry);
		const faces = {};
		for (const face of FACES) {
			if (!paths[face]) continue;
			faces[face] = {
				path: paths[face],
				tint: parseTint(block[TINT_FACE_KEYS[face]]) || baseTint
			};
		}
		return { faces: faces, weight: num(entry.Weight, 1) };
	}).filter(variant => Object.keys(variant.faces).length);

	if (variants.length) return { id: id, variants: variants, drawType: drawType, solid: false };

	// Model blocks - slabs, stairs, pillars, vines, benches - have no cube faces at all. They are not
	// cubes in game either, so a rig preview cannot be faithful to them; TextureComputedColor is the
	// average colour the game precomputes for exactly this "stand in for the block" purpose.
	const color = parseTint(block.TextureComputedColor) || parseTint(block.ParticleColor);
	if (!color) return null;
	const faces = {};
	for (const face of FACES) faces[face] = { color: color };
	return { id: id, variants: [{ faces: faces, weight: 1 }], drawType: drawType, solid: true };
};

/** Weighted pick, driven by the voxel coordinate so the result is stable. */
BlockTypeIndex.prototype.pickVariant = function (description, x, y, z) {
	const variants = description.variants;
	if (variants.length === 1) return variants[0];
	let total = 0;
	for (const variant of variants) total += variant.weight;
	let roll = hash3(x, y, z) * total;
	for (const variant of variants) {
		roll -= variant.weight;
		if (roll <= 0) return variant;
	}
	return variants[variants.length - 1];
};

// ---------------------------------------------------------------------------
// Texture atlas
//
// hytale_character reports single_texture whenever there are no collections, so every voxel face has to
// share one image. Each distinct texture-and-tint pair gets a cell and faces are UV'd into it.
// ---------------------------------------------------------------------------

function AtlasBuilder(source) {
	this.source = source;
	this.cells = new Map();
	this.order = [];
}

/**
 * One cell per distinct texture-and-tint pair, per flat colour for the non-cube blocks, or per file for
 * art that ships in the mod rather than the game assets.
 */
AtlasBuilder.prototype.request = function (face) {
	const key = face.color ? 'color:' + face.color.join(',')
		: face.file ? 'file:' + face.file
		: face.path + (face.tint ? '#' + face.tint.join(',') : '');
	if (!this.cells.has(key)) {
		const cell = {
			key: key,
			path: face.path || null,
			file: face.file || null,
			tint: face.tint || null,
			color: face.color || null,
			index: this.order.length
		};
		this.cells.set(key, cell);
		this.order.push(cell);
	}
	return this.cells.get(key);
};

function loadImage(dataUrl) {
	return new Promise((resolve, reject) => {
		const image = new Image();
		image.onload = () => resolve(image);
		image.onerror = () => reject(new Error('Could not decode image'));
		image.src = dataUrl;
	});
}

AtlasBuilder.prototype.build = async function () {
	if (!this.order.length) return null;

	const images = [];
	let cellSize = 16;
	for (const cell of this.order) {
		if (cell.color) { images.push(null); continue; }

		// A file cell is an absolute path in the mod; a path cell is relative to the game's Common/,
		// e.g. "BlockTextures/Rock_Stone.png".
		const label = cell.file || ('Common/' + cell.path);
		const buffer = cell.file ? fs.readFileSync(cell.file) : this.source.read('Common/' + cell.path);
		if (!buffer) {
			warn('Missing block texture: ' + label);
			images.push(null);
			continue;
		}
		let image;
		try {
			image = await loadImage('data:image/png;base64,' + buffer.toString('base64'));
		} catch (err) {
			warn('Could not decode ' + label);
			images.push(null);
			continue;
		}
		images.push(image);
		cellSize = Math.max(cellSize, image.width, image.height);
	}

	const columns = Math.ceil(Math.sqrt(this.order.length));
	const rows = Math.ceil(this.order.length / columns);
	const canvas = document.createElement('canvas');
	canvas.width = columns * cellSize;
	canvas.height = rows * cellSize;
	const ctx = canvas.getContext('2d');
	ctx.imageSmoothingEnabled = false;

	for (let i = 0; i < this.order.length; i++) {
		const cell = this.order[i];
		cell.x = (i % columns) * cellSize;
		cell.y = Math.floor(i / columns) * cellSize;
		cell.size = cellSize;

		const image = images[i];
		// Cells are drawn at cellSize whatever the source was, so a caller mapping UVs in the source's
		// own pixels, as a blockymodel's texture layout does, needs to know what it was scaled from.
		cell.sourceSize = image ? Math.max(image.width, image.height) : cellSize;
		if (!image) {
			// Flat-colour cells land here by design; a texture that failed to load gets magenta, because
			// a missing texture still needs a cell or the UVs of every later block shift.
			ctx.fillStyle = cell.color ? 'rgb(' + cell.color.join(',') + ')' : '#ff00ff';
			ctx.fillRect(cell.x, cell.y, cellSize, cellSize);
			continue;
		}
		ctx.drawImage(image, 0, 0, image.width, image.height, cell.x, cell.y, cellSize, cellSize);

		if (cell.tint) {
			// Multiply in place rather than overlaying, so alpha and the tint's own darkening both survive.
			const data = ctx.getImageData(cell.x, cell.y, cellSize, cellSize);
			const pixels = data.data;
			for (let p = 0; p < pixels.length; p += 4) {
				pixels[p] = (pixels[p] * cell.tint[0]) / 255;
				pixels[p + 1] = (pixels[p + 1] * cell.tint[1]) / 255;
				pixels[p + 2] = (pixels[p + 2] * cell.tint[2]) / 255;
			}
			ctx.putImageData(data, cell.x, cell.y);
		}
	}

	return { dataUrl: canvas.toDataURL('image/png'), width: canvas.width, height: canvas.height };
};

// ---------------------------------------------------------------------------
// Prefabs
// ---------------------------------------------------------------------------

/** 90-degree steps only; the index packs three of them, matching RotationTuple.index. */
const ROTATION_STEPS = [0, 90, 180, 270];

/**
 * Rotating a full cube is the same as relabelling which face shows which texture, so a rotated block
 * only needs its face map permuted. No shipped titan prefab uses this yet; it is here so one can.
 */
const FACE_NORMALS = {
	up: [0, 1, 0], down: [0, -1, 0],
	east: [1, 0, 0], west: [-1, 0, 0],
	south: [0, 0, 1], north: [0, 0, -1]
};

function faceForNormal(n) {
	for (const face of FACES) {
		const v = FACE_NORMALS[face];
		if (Math.abs(v[0] - n[0]) < 0.5 && Math.abs(v[1] - n[1]) < 0.5 && Math.abs(v[2] - n[2]) < 0.5) return face;
	}
	return null;
}

function rotateFaceMap(faces, rotationIndex) {
	if (!rotationIndex) return faces;
	const yaw = ROTATION_STEPS[rotationIndex % 4];
	const pitch = ROTATION_STEPS[Math.floor(rotationIndex / 4) % 4];
	const roll = ROTATION_STEPS[Math.floor(rotationIndex / 16) % 4];
	const euler = new THREE.Euler(Math.degToRad(pitch), Math.degToRad(yaw), Math.degToRad(roll), 'ZYX');
	const matrix = new THREE.Matrix4().makeRotationFromEuler(euler);

	const out = {};
	for (const face of FACES) {
		if (!faces[face]) continue;
		const n = FACE_NORMALS[face];
		const rotated = new THREE.Vector3(n[0], n[1], n[2]).applyMatrix4(matrix);
		const target = faceForNormal([Math.round(rotated.x), Math.round(rotated.y), Math.round(rotated.z)]);
		if (target) out[target] = faces[face];
	}
	return out;
}

/**
 * Turns a prefab about Y as it is read, mirroring PrefabRotation in the engine.
 *
 * <p>A bone's PrefabYaw exists because a prefab is authored facing whichever way was convenient to build
 * and the rig expects one particular forward. The mod turns the blocks as it reads them rather than
 * rotating the bone, so a bone's Pivot is expressed in these turned coordinates — which is why this has to
 * happen before the bounds and the default pivot are worked out, or the preview would disagree with the
 * game about where every bone hangs from.
 *
 * @param steps quarter turns, 0-3, matching PrefabRotation.ROTATION_90 and friends
 */
function rotatePrefabBlocks(blocks, steps) {
	if (!steps) return blocks;

	return blocks.map(block => {
		let x = block.x;
		let z = block.z;
		for (let turn = 0; turn < steps; turn++) {
			const previousX = x;
			x = z;
			z = -previousX;
		}

		// A stair or a door carries its own facing in the packed rotation index, whose lowest digit is the
		// yaw. Turning the block set without turning those would leave a door in a wall it no longer faces.
		const yaw = (block.rotation % 4 + steps) % 4;

		return Object.assign({}, block, { x: x, z: z, rotation: block.rotation - (block.rotation % 4) + yaw });
	});
}

/** Quarter turns for a bone's PrefabYaw in degrees, tolerating negatives and multiples of 360. */
function prefabYawSteps(degrees) {
	return Math.round((((degrees % 360) + 360) % 360) / 90) % 4;
}

function readPrefab(repoRoot, key, yawSteps) {
	const path = joinPath(repoRoot, REPO.prefabs, key.replace(/\//g, nodePath.sep) + '.prefab.json');
	if (!fileExists(path)) return null;

	const doc = readJsonFile(path);
	const blocks = rotatePrefabBlocks((doc.blocks || []).map(block => ({
		x: num(block.x, 0),
		y: num(block.y, 0),
		z: num(block.z, 0),
		// A "50%Rock_Stone" name means a chance-placed block; the preview always shows it.
		name: String(block.name || '').replace(/^\d+%/, ''),
		rotation: num(block.rotation, 0)
	})), yawSteps || 0);

	if (!blocks.length) return { key: key, blocks: blocks, pivot: [0, 0, 0] };

	let minX = Infinity, minY = Infinity, minZ = Infinity;
	let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
	for (const block of blocks) {
		minX = Math.min(minX, block.x); maxX = Math.max(maxX, block.x);
		minY = Math.min(minY, block.y); maxY = Math.max(maxY, block.y);
		minZ = Math.min(minZ, block.z); maxZ = Math.max(maxZ, block.z);
	}

	return {
		key: key,
		blocks: blocks,
		// PrefabVoxels.defaultPivot: bottom centre of the bounds, in continuous coordinates where a
		// voxel indexed 3 spans 3.0 to 4.0.
		pivot: [(minX + maxX + 1) / 2, minY, (minZ + maxZ + 1) / 2]
	};
}

// ---------------------------------------------------------------------------
// Skeleton model
// ---------------------------------------------------------------------------

function loadSkeleton(repoRoot, id) {
	const path = joinPath(repoRoot, REPO.skeletons, id + '.json');
	if (!fileExists(path)) throw new Error('No skeleton named "' + id + '" under ' + REPO.skeletons);
	return { id: id, path: path, raw: fs.readFileSync(path, 'utf8'), doc: readJsonFile(path) };
}

function loadVariant(repoRoot, id) {
	if (!id) return null;
	const path = joinPath(repoRoot, REPO.variants, id + '.json');
	if (!fileExists(path)) return null;
	return { id: id, path: path, doc: readJsonFile(path) };
}

function loadClipSet(repoRoot, id) {
	if (!id) return null;
	const path = joinPath(repoRoot, REPO.clips, id + '.json');
	if (!fileExists(path)) return null;
	return { id: id, path: path, raw: fs.readFileSync(path, 'utf8'), doc: readJsonFile(path) };
}

// ---------------------------------------------------------------------------
// Weakpoint model
//
// The ore a weakpoint renders is a ModelAsset in the mod: a .blockymodel of boxes plus a texture, both
// under Common/. Drawing it on each socket is the only way to judge whether a node will clip into the
// body or hang off it, which is the whole reason for authoring sockets by hand.
// ---------------------------------------------------------------------------

/**
 * Units per block the client renders a ModelAsset's mesh at.
 *
 * <p>The grid is not recorded in a .blockymodel and is not a property of the file: it is the convention
 * of whatever draws it. Block art is authored at 32, which is why 290 of the 1030 meshes under Blocks/
 * measure exactly 32 units across, but an entity model is drawn at 64. Player.blockymodel is 102 units
 * tall, which is a 1.6-block player at 64 and an absurd 3.2-block one at 32.
 *
 * <p>This matters here because the ore is block art, from Resources/Ores/, pressed into service as an
 * entity model, so the client draws it at half the size it was authored at.
 */
const ENTITY_UNITS_PER_BLOCK = 64;

/** Model ids are flat but the files are foldered, so the id is searched for rather than joined. */
function findModelAsset(repoRoot, id) {
	const root = joinPath(repoRoot, REPO.models);
	const stack = [root];
	while (stack.length) {
		const dir = stack.pop();
		let entries;
		try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (err) { continue; }
		for (const entry of entries) {
			const path = joinPath(dir, entry.name);
			if (entry.isDirectory()) stack.push(path);
			else if (entry.name === id + '.json') return path;
		}
	}
	return null;
}

/**
 * Flattens a .blockymodel into boxes in its own units.
 *
 * Mirrors the walk in tools/measure_ore_model.py: a child's position adds to its parent's without being
 * turned by it, and each box is its own offset and half extents rotated by its own orientation.
 */
function readBlockyModel(path) {
	const doc = readJsonFile(path);
	const boxes = [];

	const walk = (node, parentPos) => {
		const p = node.position || {};
		const at = [
			parentPos[0] + num(p.x, 0),
			parentPos[1] + num(p.y, 0),
			parentPos[2] + num(p.z, 0)
		];

		const shape = node.shape;
		if (shape && shape.type === 'box' && shape.visible !== false) {
			const size = (shape.settings || {}).size || {};
			const stretch = shape.stretch || {};
			const offset = shape.offset || {};
			const dims = ['x', 'y', 'z'].map(axis => num(size[axis], 0) * num(stretch[axis], 1));
			const q = node.orientation || {};
			boxes.push({
				name: String(node.name || 'Box'),
				at: at,
				offset: [num(offset.x, 0), num(offset.y, 0), num(offset.z, 0)],
				half: dims.map(d => d / 2),
				// Face UVs are the box's unstretched size in texture pixels, laid out by textureLayout.
				uvSize: ['x', 'y', 'z'].map(axis => num(size[axis], 0)),
				rotation: quatToEuler([num(q.x, 0), num(q.y, 0), num(q.z, 0), num(q.w, 1)], 'ZYX'),
				layout: shape.textureLayout || {}
			});
		}

		for (const child of node.children || []) walk(child, at);
	};

	for (const root of doc.nodes || []) walk(root, [0, 0, 0]);
	return boxes;
}

/**
 * Resolves the ore a variant hangs on its sockets, into everything needed to draw it in model units.
 *
 * @return {@code null} when the variant declares no model, which is how a skeleton whose weakpoints are
 *         its own blocks, like the Yaga egg's shell, opts out.
 */
function loadWeakpointModel(repoRoot, variantDoc, skeletonDoc) {
	if (!variantDoc) return null;
	const id = variantDoc.WeakpointModel ? String(variantDoc.WeakpointModel) : null;
	if (!id) return null;

	const assetPath = findModelAsset(repoRoot, id);
	if (!assetPath) { warn('No ModelAsset named "' + id + '" under ' + REPO.models); return null; }

	const asset = readJsonFile(assetPath);
	if (!asset.Model) { warn('ModelAsset "' + id + '" declares no Model'); return null; }

	const modelPath = joinPath(repoRoot, REPO.common, String(asset.Model).replace(/\//g, nodePath.sep));
	if (!fileExists(modelPath)) { warn('Missing blockymodel: ' + asset.Model); return null; }

	const boxes = readBlockyModel(modelPath);
	if (!boxes.length) { warn('Blockymodel "' + asset.Model + '" has no boxes'); return null; }

	// The rig is authored in model units and the runtime scales it at the root, so the ore has to come
	// back the same way: out of blockymodel units into world blocks, then out of the titan's own scale.
	const bodyScale = num(variantDoc.BodyScale, 1) || 1;
	const unitScale = num(skeletonDoc.UnitScale, 1) || 1;
	const weakpointScale = num(variantDoc.WeakpointScale, 1);
	const perUnit = weakpointScale / ENTITY_UNITS_PER_BLOCK / (unitScale * bodyScale);

	// TitanSpawner backs the node down its own axis so the model's centre, not its origin, lands on the
	// socket, then WeakpointEmbed beds it in. A negative embed is what makes a node stand proud.
	//
	// Read from the declared HitBox rather than the mesh, because that is what the spawner reads. When
	// the two disagree, as they do for art authored on the block grid, the preview should show where the
	// node really ends up, not where it was meant to.
	const box = asset.HitBox || {};
	const centreY = (num((box.Min || {}).Y, 0) + num((box.Max || {}).Y, 0)) * 0.5;
	const sink = centreY * weakpointScale / (unitScale * bodyScale) + num(variantDoc.WeakpointEmbed, 0);

	let texturePath = null;
	if (asset.Texture) {
		const candidate = joinPath(repoRoot, REPO.common, String(asset.Texture).replace(/\//g, nodePath.sep));
		if (fileExists(candidate)) texturePath = candidate;
		else warn('Missing weakpoint texture: ' + asset.Texture);
	}

	return {
		id: id,
		boxes: boxes,
		perUnit: perUnit,
		sink: sink,
		texturePath: texturePath,
		// A titan only ever wears WeakpointCountMin..Max of its sockets; the preview shows the busiest.
		count: Math.max(1, num(variantDoc.WeakpointCountMax, num(variantDoc.WeakpointCountMin, 1)))
	};
}

/**
 * Picks which sockets to hang preview ore on.
 *
 * <p>TitanSpawner shuffles and then rejects picks that crowd each other, so any well spread set is a
 * spawn you could actually get. This takes the most spread one instead of a random one: reshuffling on
 * every reimport would make placements impossible to compare, and walking the list in file order lands
 * three of Stone_Talus's four on the top face, which shows nothing about the flanks.
 *
 * <p>Distance only counts between sockets on the same bone, as it does in the spawner, because offsets
 * are bone-local and the matching spot on four identical limbs would otherwise read as one point.
 */
function chooseSocketSample(sockets, wanted) {
	if (wanted >= sockets.length) return new Set(sockets.map((_, i) => i));

	const offsets = sockets.map(socket => vec(socket.Offset));
	const bones = sockets.map(socket => String(socket.Bone));
	const apart = (a, b) => {
		if (bones[a] !== bones[b]) return Infinity;
		const dx = offsets[a][0] - offsets[b][0];
		const dy = offsets[a][1] - offsets[b][1];
		const dz = offsets[a][2] - offsets[b][2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	};

	const chosen = [0];
	while (chosen.length < wanted) {
		let best = -1;
		let bestGap = -1;
		for (let i = 0; i < sockets.length; i++) {
			if (chosen.indexOf(i) !== -1) continue;
			let gap = Infinity;
			for (const other of chosen) gap = Math.min(gap, apart(i, other));
			if (gap > bestGap) { bestGap = gap; best = i; }
		}
		if (best < 0) break;
		chosen.push(best);
	}
	return new Set(chosen);
}

/**
 * Resolves bind offsets into absolute positions and works out which bones the runtime will drive with
 * IK, which the importer uses to flag them in the outliner.
 */
function buildBoneTable(skeletonDoc) {
	const bones = (skeletonDoc.Bones || []).map((def, index) => ({
		index: index,
		name: String(def.Name || ('Bone' + index)),
		parent: def.Parent ? String(def.Parent) : null,
		offset: vec(def.Offset),
		rotation: vec(def.Rotation),
		prefab: def.Prefab ? String(def.Prefab) : null,
		prefabYaw: num(def.PrefabYaw, 0),
		pivot: def.Pivot ? vec(def.Pivot) : null,
		scale: num(def.Scale, 1),
		mirrorX: def.MirrorX === true,
		sliceMinY: typeof def.SliceMinY === 'number' ? def.SliceMinY : null,
		sliceMaxY: typeof def.SliceMaxY === 'number' ? def.SliceMaxY : null,
		def: def
	}));

	const byName = new Map();
	for (const bone of bones) byName.set(bone.name, bone);

	// Blockbench applies a parent group's rotation to its children itself, so origins accumulate as
	// plain sums here exactly as they do in the blockymodel codec.
	for (const bone of bones) {
		const parent = bone.parent ? byName.get(bone.parent) : null;
		if (bone.parent && !parent) warn('Bone "' + bone.name + '" references missing parent "' + bone.parent + '"');
		const base = parent && parent.origin ? parent.origin : [0, 0, 0];
		bone.origin = [base[0] + bone.offset[0], base[1] + bone.offset[1], base[2] + bone.offset[2]];
	}

	const ikRoles = new Map();
	for (const chain of (skeletonDoc.IkChains || [])) {
		const role = String(chain.Role || '');
		for (const name of (chain.Bones || [])) {
			if (!ikRoles.has(name)) ikRoles.set(name, role);
		}
	}
	for (const bone of bones) bone.ikRole = ikRoles.get(bone.name) || null;

	return { bones: bones, byName: byName };
}

// ---------------------------------------------------------------------------
// Rig construction
// ---------------------------------------------------------------------------

/** Rock variants swap a bone's prefab for a suffixed one, falling back when that suffix has no file. */
function resolvePrefabKey(repoRoot, key, rockType) {
	if (rockType) {
		const suffixed = key + '_' + rockType;
		if (fileExists(joinPath(repoRoot, REPO.prefabs, suffixed.replace(/\//g, nodePath.sep) + '.prefab.json'))) {
			return suffixed;
		}
	}
	return key;
}

const GUIDE_GROUP_NAME = 'Titan Guides';
const SOCKET_GROUP_NAME = 'Weakpoint Sockets';
const COLOR_IK_FOOT = 4;
const COLOR_IK_HAND = 2;
const COLOR_GUIDE = 6;
const COLOR_SOCKET = 1;
// No Hytale block is anywhere near this, which is the point.
const SOCKET_RGB = [255, 0, 170];
const SOCKET_MARKER_WIDTH = 0.4;
const SOCKET_MARKER_LENGTH = 1.2;

async function buildRig(options) {
	const repoRoot = options.repoRoot;
	const skeleton = loadSkeleton(repoRoot, options.skeletonId);
	const variant = loadVariant(repoRoot, options.variantId);
	const rockType = variant && variant.doc.RockType ? String(variant.doc.RockType) : null;
	const table = buildBoneTable(skeleton.doc);

	const clipSetId = skeleton.doc.ClipSet ? String(skeleton.doc.ClipSet) : null;
	const clipSet = loadClipSet(repoRoot, clipSetId);

	// Read the prefabs first. They live in the mod, so this stage cannot fail on a bad asset root and
	// any missing prefab is reported even when the block textures are unavailable.
	const bonePrefabs = new Map();
	const missingPrefabs = [];
	for (const bone of table.bones) {
		if (!bone.prefab) continue;
		const key = resolvePrefabKey(repoRoot, bone.prefab, rockType);
		const prefab = readPrefab(repoRoot, key, prefabYawSteps(bone.prefabYaw));
		if (!prefab) { missingPrefabs.push(bone.name + ' -> ' + key); continue; }

		const blocks = prefab.blocks.filter(block =>
			(bone.sliceMinY == null || block.y >= bone.sliceMinY) &&
			(bone.sliceMaxY == null || block.y <= bone.sliceMaxY));
		bonePrefabs.set(bone.name, { key: key, pivot: bone.pivot || prefab.pivot, blocks: blocks });
	}

	// Resolve those blocks and reserve an atlas cell per texture, so the atlas is complete before any
	// cube exists to point into it.
	const source = openAssetSource(options.assetRoot);
	let atlasResult = null;
	const perBoneVoxels = new Map();
	const missingBlocks = new Set();

	// The ore hung on each socket, so a node that would clip into the body is visible while authoring.
	const ore = (skeleton.doc.WeakpointSockets || []).length
		? loadWeakpointModel(repoRoot, variant && variant.doc, skeleton.doc)
		: null;

	let socketCell = null;
	let oreCell = null;
	try {
		const index = new BlockTypeIndex(source);
		const atlas = new AtlasBuilder(source);

		if (ore && ore.texturePath) oreCell = atlas.request({ file: ore.texturePath });
		// With no ore to draw, sockets fall back to spikes in a flat colour of their own rather than
		// borrowing a block texture that would camouflage them against the body.
		else if ((skeleton.doc.WeakpointSockets || []).length) socketCell = atlas.request({ color: SOCKET_RGB });

		for (const [boneName, data] of bonePrefabs) {
			const voxels = [];
			for (const block of data.blocks) {
				const description = index.describe(block.name);
				if (!description) { missingBlocks.add(block.name); continue; }

				const variantPick = index.pickVariant(description, block.x, block.y, block.z);
				const faces = rotateFaceMap(variantPick.faces, block.rotation);
				const cells = {};
				for (const face of FACES) {
					if (!faces[face]) continue;
					cells[face] = atlas.request(faces[face]);
				}
				voxels.push({ block: block, cells: cells });
			}
			perBoneVoxels.set(boneName, { pivot: data.pivot, voxels: voxels, key: data.key });
		}

		atlasResult = await atlas.build();
	} finally {
		source.close();
	}

	// Pass two: build the Blockbench scene.
	setupProject(Formats.hytale_character);
	Project.name = options.skeletonId;

	let texture = null;
	if (atlasResult) {
		texture = new Texture({ name: 'Titan_Blocks', keep_size: true }).fromDataURL(atlasResult.dataUrl).add(false);
		texture.uv_width = atlasResult.width;
		texture.uv_height = atlasResult.height;
		texture.use_as_default = true;
		Project.texture_width = atlasResult.width;
		Project.texture_height = atlasResult.height;
	}

	const groups = new Map();
	for (const bone of table.bones) {
		const parentGroup = bone.parent ? groups.get(bone.parent) : null;
		const group = new Group({
			name: bone.name,
			origin: bone.origin.slice(),
			rotation: bindRotationToGroup(bone.rotation)
		});
		group.addTo(parentGroup || 'root');
		group.init();
		// Legs are re-solved every tick by TitanAnimationSystem, so anything keyed on them during a gait
		// is discarded. Colouring them is the cheapest way to stop that work being done by accident.
		if (bone.ikRole === 'Foot') group.color = COLOR_IK_FOOT;
		else if (bone.ikRole === 'Hand') group.color = COLOR_IK_HAND;
		groups.set(bone.name, group);
	}

	let cubeCount = 0;
	for (const bone of table.bones) {
		const data = perBoneVoxels.get(bone.name);
		if (!data || !data.voxels.length) continue;

		const group = groups.get(bone.name);
		const pivot = data.pivot;
		const scale = bone.scale;
		const half = scale / 2;
		const mirror = bone.mirrorX ? -1 : 1;

		for (const voxel of data.voxels) {
			const block = voxel.block;
			// TitanSpawner.spawnParts: the voxel's centre, measured from the bone's pivot, in model units.
			const cx = group.origin[0] + (block.x + 0.5 - pivot[0]) * scale * mirror;
			const cy = group.origin[1] + (block.y + 0.5 - pivot[1]) * scale;
			const cz = group.origin[2] + (block.z + 0.5 - pivot[2]) * scale;

			const cube = new Cube({
				name: block.name,
				autouv: 0,
				box_uv: false,
				from: [cx - half, cy - half, cz - half],
				to: [cx + half, cy + half, cz + half]
			});
			cube.addTo(group);

			for (const face of FACES) {
				const cell = voxel.cells[face];
				if (!cell || !texture) {
					cube.faces[face].texture = null;
					cube.faces[face].uv = [0, 0, 0, 0];
					continue;
				}
				cube.faces[face].texture = texture.uuid;
				cube.faces[face].uv = [cell.x, cell.y, cell.x + cell.size, cell.y + cell.size];
			}
			cube.init();
			cubeCount++;
		}
	}

	if (ore && oreCell) ore.textureSize = oreCell.sourceSize;
	buildSockets(skeleton.doc, table, texture, socketCell, ore, oreCell);
	buildGuides(skeleton.doc, table);

	Project.titan = {
		repoRoot: repoRoot,
		skeletonId: skeleton.id,
		skeletonPath: skeleton.path,
		skeletonRaw: skeleton.raw,
		variantId: variant ? variant.id : null,
		clipSetId: clipSetId,
		clipSetPath: clipSet ? clipSet.path : null,
		clipSetRaw: clipSet ? clipSet.raw : null,
		clipSetDoc: clipSet ? clipSet.doc : null,
		bones: table.bones.map(bone => bone.name)
	};
	// Bone metadata that has no Blockbench equivalent rides on the group so the properties dialog and
	// the write-back can both find it after a reload.
	for (const bone of table.bones) {
		const group = groups.get(bone.name);
		if (group) group.titan_bone = bone;
	}

	let clipCount = 0;
	if (clipSet) clipCount = importClipSet(repoRoot, clipSet, options.skipMissingClips !== false);

	Canvas.updateAll();
	updateSelection();

	return {
		bones: table.bones.length,
		cubes: cubeCount,
		clips: clipCount,
		atlasCells: atlasResult ? 'atlas ' + atlasResult.width + 'x' + atlasResult.height : 'no textures',
		missingBlocks: Array.from(missingBlocks),
		missingPrefabs: missingPrefabs
	};
}

function paintMarker(cube, texture, cell) {
	for (const face of FACES) {
		if (texture && cell) {
			cube.faces[face].texture = texture.uuid;
			cube.faces[face].uv = [cell.x, cell.y, cell.x + cell.size, cell.y + cell.size];
		} else {
			cube.faces[face].texture = null;
			cube.faces[face].uv = [0, 0, 0, 0];
		}
	}
}

function marker(parent, name, position, size) {
	const h = size / 2;
	const cube = new Cube({
		name: name,
		autouv: 0,
		box_uv: false,
		from: [position[0] - h, position[1] - h, position[2] - h],
		to: [position[0] + h, position[1] + h, position[2] + h]
	});
	cube.addTo(parent);
	paintMarker(cube, null, null);
	cube.init();
	return cube;
}

/**
 * Weakpoint sockets are editable: drag one and "Save Titan Skeleton" writes its new Offset back, so
 * they are visible geometry in their own group rather than a hidden guide.
 *
 * Each cube carries its socket index, because the write-back has to know which entry of
 * WeakpointSockets it came from, and that has to survive renaming and reordering in the outliner.
 */
/** blockymodel face names to Blockbench's, for a model whose front looks down -Z. */
const ORE_FACES = { top: 'up', bottom: 'down', front: 'north', back: 'south', right: 'east', left: 'west' };
// Which two of the box's dimensions each face spans, as indices into [x, y, z].
const ORE_FACE_AXES = { up: [0, 2], down: [0, 2], north: [0, 1], south: [0, 1], east: [2, 1], west: [2, 1] };

/**
 * Lays one blockymodel face into its slot of the ore texture's atlas cell.
 *
 * <p>A quarter-turned face samples a transposed rect, so the box's two dimensions swap before the offset
 * is applied. Offsets also run off the end of the texture and rely on it repeating, which is free on a
 * texture of its own but in an atlas would read the cell next door, so they wrap here instead.
 */
function oreFaceUv(layout, width, height, cell, sourceSize) {
	const size = sourceSize || cell.size;
	const scale = cell.size / size;
	const angle = ((Math.round(num(layout.angle, 0) / 90) % 4) + 4) % 4;
	if (angle % 2) { const swap = width; width = height; height = swap; }

	const ox = ((num((layout.offset || {}).x, 0) % size) + size) % size;
	const oy = ((num((layout.offset || {}).y, 0) % size) + size) % size;

	const mirror = layout.mirror || {};
	let x1 = ox, x2 = ox + width, y1 = oy, y2 = oy + height;
	if (mirror.x) { const swap = x1; x1 = x2; x2 = swap; }
	if (mirror.y) { const swap = y1; y1 = y2; y2 = swap; }

	return {
		uv: [cell.x + x1 * scale, cell.y + y1 * scale, cell.x + x2 * scale, cell.y + y2 * scale],
		rotation: angle * 90
	};
}

/**
 * Draws the ore a weakpoint renders, in the socket group's unrotated frame. The group carries the
 * facing, so the model is laid out as though the socket pointed straight up and TitanSpawner's sink is
 * applied along +Y; turning the group then lands every box where the runtime would put it.
 */
function buildOre(parent, at, ore, texture, cell) {
	const base = [at[0], at[1] - ore.sink, at[2]];
	const k = ore.perUnit;

	for (const box of ore.boxes) {
		const centre = [0, 1, 2].map(axis => base[axis] + box.at[axis] * k);
		const cube = new Cube({
			name: box.name,
			autouv: 0,
			box_uv: false,
			origin: centre.slice(),
			rotation: box.rotation.slice(),
			from: [0, 1, 2].map(axis => centre[axis] + (box.offset[axis] - box.half[axis]) * k),
			to: [0, 1, 2].map(axis => centre[axis] + (box.offset[axis] + box.half[axis]) * k)
		});
		cube.addTo(parent);

		for (const source in ORE_FACES) {
			const face = ORE_FACES[source];
			const layout = box.layout[source];
			if (!layout || !texture || !cell) {
				cube.faces[face].texture = null;
				cube.faces[face].uv = [0, 0, 0, 0];
				continue;
			}
			const axes = ORE_FACE_AXES[face];
			const laid = oreFaceUv(layout, box.uvSize[axes[0]], box.uvSize[axes[1]], cell, ore.textureSize);
			cube.faces[face].texture = texture.uuid;
			cube.faces[face].uv = laid.uv;
			cube.faces[face].rotation = laid.rotation;
		}
		cube.init();
	}
}

/** The stand-in when a variant hangs no ore on its sockets, or none was picked in the import dialog. */
function buildSpike(parent, at, texture, cell) {
	const half = SOCKET_MARKER_WIDTH / 2;
	const cube = new Cube({
		name: 'Marker',
		autouv: 0,
		box_uv: false,
		origin: at.slice(),
		from: [at[0] - half, at[1], at[2] - half],
		to: [at[0] + half, at[1] + SOCKET_MARKER_LENGTH, at[2] + half]
	});
	cube.addTo(parent);
	paintMarker(cube, texture, cell);
	cube.init();
}

function buildSockets(skeletonDoc, table, texture, cell, ore, oreCell) {
	const sockets = skeletonDoc.WeakpointSockets || [];
	if (!sockets.length) return;

	const root = new Group({ name: SOCKET_GROUP_NAME, origin: [0, 0, 0] });
	root.addTo('root');
	root.init();
	root.color = COLOR_SOCKET;

	// Every socket wears its ore, but only as many as a titan actually rolls start visible: showing all
	// seventeen at once buries the body under ore no single titan ever carries. The rest are a click
	// away in the outliner, which is how you check one candidate against its neighbours.
	const shown = ore ? chooseSocketSample(sockets, ore.count) : null;

	sockets.forEach((socket, index) => {
		const boneName = String(socket.Bone);
		const bone = table.byName.get(boneName);
		if (!bone) return;

		const offset = vec(socket.Offset);
		const at = [
			bone.origin[0] + offset[0],
			bone.origin[1] + offset[1],
			bone.origin[2] + offset[2]
		];
		// Same fallback the spawner uses: an undeclared normal points straight out from the bone pivot.
		const normal = socket.Normal ? vec(socket.Normal) : (derivedNormal(offset) || [0, 1, 0]);

		// A group rather than a cube: the ore is several boxes with rotations of their own, and the
		// socket's facing has to turn all of them together.
		const group = new Group({
			name: 'Socket_' + index + '_' + boneName,
			origin: at.slice(),
			rotation: normalToMarkerRotation(normal)
		});
		group.addTo(root);
		group.init();
		group.color = COLOR_SOCKET;
		// The imported facing is kept so the write-back can tell a socket the author turned from one that
		// merely inherited its direction from the offset, and only pin a Normal on the former.
		group.titan_socket = {
			index: index,
			bone: boneName,
			declared: !!socket.Normal,
			rotation: group.rotation.map(round4)
		};

		if (ore) {
			buildOre(group, at, ore, texture, oreCell);
			// Set on the boxes as well as the group: the outliner's eye drives the children, and the
			// canvas only reads a cube's own flag.
			if (!shown.has(index)) {
				group.visibility = false;
				for (const child of group.children) child.visibility = false;
			}
		} else {
			buildSpike(group, at, texture, cell);
		}
	});
}

/**
 * IK rest positions are numbers in the skeleton that are hard to picture, but nothing reads them back,
 * so they stay hidden markers in a non-exporting group.
 */
function buildGuides(skeletonDoc, table) {
	const chains = skeletonDoc.IkChains || [];
	if (!chains.length) return;

	const root = new Group({ name: GUIDE_GROUP_NAME, origin: [0, 0, 0] });
	root.addTo('root');
	root.init();
	root.color = COLOR_GUIDE;
	root.export = false;
	root.visibility = false;

	const group = new Group({ name: 'IK Rest Targets', origin: [0, 0, 0] });
	group.addTo(root);
	group.init();
	group.export = false;

	const bodyBone = table.byName.get(String(skeletonDoc.BodyBone || ''));
	const base = bodyBone ? bodyBone.origin : [0, 0, 0];
	for (const chain of chains) {
		const offset = vec(chain.RestOffset);
		const cube = marker(group, String(chain.Name || 'Chain') + '_Rest', [
			base[0] + offset[0],
			base[1] + offset[1],
			base[2] + offset[2]
		], 0.7);
		cube.export = false;
		cube.visibility = false;
	}
}

// ---------------------------------------------------------------------------
// Clip import
//
// The official plugin parses .blockyanim on drag and on open but does not expose the function, so this
// mirrors it. Export is deliberately not mirrored: setting animation.path is enough for the official
// plugin's patched Animation.prototype.save to write the file back where it came from.
// ---------------------------------------------------------------------------

const BBAnimation = window.Animation;

function parseBlockyAnim(name, path, content) {
	const animation = new BBAnimation({
		name: name,
		length: num(content.duration, 0) / FPS,
		loop: content.holdLastKeyframe ? 'hold' : 'loop',
		path: path,
		snapping: FPS
	});

	const channels = [
		['rotation', 'orientation'],
		['position', 'position'],
		['scale', 'shapeStretch'],
		['visibility', 'shapeVisible'],
		['uv_offset', 'shapeUvOffset']
	];
	const quaternion = new THREE.Quaternion();
	const euler = new THREE.Euler(0, 0, 0, 'ZYX');

	const nodes = content.nodeAnimations || {};
	for (const boneName of Object.keys(nodes)) {
		const node = nodes[boneName];
		const group = Group.all.find(g => g.name === boneName);
		const animator = new BoneAnimator(group ? group.uuid : guid(), animation, boneName);
		animation.animators[animator.uuid] = animator;
		animator.group = group;

		for (const [channel, key] of channels) {
			const keyframes = node[key];
			if (!Array.isArray(keyframes) || !keyframes.length) continue;

			for (const source of keyframes) {
				const delta = source.delta;
				let point;
				if (channel === 'visibility') {
					point = { visibility: delta === true };
				} else if (channel === 'uv_offset') {
					point = { x: num(delta && delta.x, 0), y: -num(delta && delta.y, 0) };
				} else if (channel === 'rotation') {
					quaternion.set(num(delta && delta.x, 0), num(delta && delta.y, 0), num(delta && delta.z, 0), num(delta && delta.w, 1));
					euler.setFromQuaternion(quaternion.normalize(), 'ZYX');
					point = { x: Math.radToDeg(euler.x), y: Math.radToDeg(euler.y), z: Math.radToDeg(euler.z) };
				} else {
					point = { x: num(delta && delta.x, 0), y: num(delta && delta.y, 0), z: num(delta && delta.z, 0) };
				}
				animator.addKeyframe({
					time: num(source.time, 0) / FPS,
					channel: channel,
					interpolation: source.interpolationType === 'linear' ? 'linear' : 'catmullrom',
					data_points: [point]
				});
			}
		}
	}

	animation.add(false);
	animation.saved = true;
	return animation;
}

function importClipSet(repoRoot, clipSet, skipMissing) {
	const animations = clipSet.doc.Animations || {};
	let count = 0;

	for (const logicalName of Object.keys(animations)) {
		const entry = animations[logicalName];
		const file = entry && entry.File;
		if (!file) continue;

		const path = joinPath(repoRoot, REPO.common, String(file).replace(/\//g, nodePath.sep));
		if (!fileExists(path)) {
			// Borrowed vanilla clips such as Characters/Animations/Emote/Dance_Boogie live in the game
			// assets, not the mod, so their absence here is expected rather than an error.
			if (!skipMissing) warn('Clip file not found: ' + path);
			continue;
		}

		try {
			// Named for the clip set key rather than the file, because that is the name the runtime and
			// the /titan anim command use.
			const animation = parseBlockyAnim(logicalName, path, readJsonFile(path));
			animation.titan_clip = logicalName;
			count++;
		} catch (err) {
			warn('Could not load clip "' + logicalName + '": ' + err.message);
		}
	}

	if (count && !BBAnimation.selected && Animator.open) {
		BBAnimation.all[0].select();
	}
	return count;
}

// ---------------------------------------------------------------------------
// JSON write-back
//
// The skeleton file is maintained by hand. Its $Comment blocks carry the reasoning behind the rig, blank
// lines separate the left and right limbs, and numbers are written as 5.0 where that reads better. None
// of that survives a parse-and-reprint, so instead the parser records where every value came from and an
// edit is a text splice over the original. A field nobody touched keeps its bytes exactly.
// ---------------------------------------------------------------------------

function JsonDocument(text) {
	this.text = String(text).replace(/^\uFEFF/, '');
	// A Windows checkout of the mod has CRLF on disk whatever .editorconfig asks for, and an inserted
	// line that disagrees with the rest of the file shows up as a whitespace change in every later diff.
	this.newline = this.text.indexOf('\r\n') >= 0 ? '\r\n' : '\n';
	this.edits = [];
	this.nodes = new Map();
	this.value = this.parseDocument();
}

/**
 * Recursive-descent parse that keeps a node per value, addressed by a path like "/Bones/3/Offset".
 * Object members additionally record where their key starts and where the preceding comma ended, which
 * is what makes clean insertion and removal possible later.
 */
JsonDocument.prototype.parseDocument = function () {
	const text = this.text;
	const self = this;
	let i = 0;

	function fail(message) {
		throw new Error('JSON parse error at offset ' + i + ': ' + message);
	}
	function skipSpace() {
		while (i < text.length && (text[i] === ' ' || text[i] === '\t' || text[i] === '\r' || text[i] === '\n')) i++;
	}
	function readString() {
		if (text[i] !== '"') fail('expected a string');
		const start = i++;
		while (i < text.length) {
			if (text[i] === '\\') { i += 2; continue; }
			if (text[i] === '"') { i++; break; }
			i++;
		}
		return JSON.parse(text.slice(start, i));
	}

	function readValue(path) {
		skipSpace();
		const node = { path: path, start: i };

		if (text[i] === '{') {
			node.type = 'object';
			node.members = new Map();
			node.memberList = [];
			const value = {};
			i++;
			let separatorStart = i;
			skipSpace();
			if (text[i] === '}') {
				i++;
			} else {
				for (;;) {
					skipSpace();
					const memberStart = i;
					const key = readString();
					skipSpace();
					if (text[i] !== ':') fail('expected ":"');
					i++;
					const child = readValue(path + '/' + key);
					const member = {
						key: key,
						index: node.memberList.length,
						separatorStart: separatorStart,
						memberStart: memberStart,
						memberEnd: i,
						valueStart: child.start,
						valueEnd: child.end
					};
					node.members.set(key, member);
					node.memberList.push(member);
					value[key] = child.value;
					skipSpace();
					if (text[i] === ',') { i++; separatorStart = i; continue; }
					if (text[i] === '}') { i++; break; }
					fail('expected "," or "}"');
				}
			}
			node.value = value;
		} else if (text[i] === '[') {
			node.type = 'array';
			const value = [];
			i++;
			skipSpace();
			if (text[i] === ']') {
				i++;
			} else {
				for (;;) {
					const child = readValue(path + '/' + value.length);
					value.push(child.value);
					skipSpace();
					if (text[i] === ',') { i++; continue; }
					if (text[i] === ']') { i++; break; }
					fail('expected "," or "]"');
				}
			}
			node.value = value;
		} else if (text[i] === '"') {
			node.type = 'string';
			node.value = readString();
		} else {
			const start = i;
			while (i < text.length && ' \t\r\n,}]'.indexOf(text[i]) === -1) i++;
			const raw = text.slice(start, i);
			node.type = 'literal';
			if (raw === 'true') node.value = true;
			else if (raw === 'false') node.value = false;
			else if (raw === 'null') node.value = null;
			else {
				node.value = Number(raw);
				if (!isFinite(node.value)) fail('bad literal "' + raw + '"');
			}
		}

		node.end = i;
		self.nodes.set(path, node);
		return node;
	}

	const root = readValue('');
	skipSpace();
	if (i < text.length) fail('trailing content');
	return root.value;
};

JsonDocument.prototype.node = function (path) {
	return this.nodes.get(path);
};

JsonDocument.prototype.splice = function (start, end, text) {
	this.edits.push({ start: start, end: end, text: text });
};

/** The leading whitespace of the line containing this offset, so inserts line up with their siblings. */
JsonDocument.prototype.indentAt = function (position) {
	let lineStart = position;
	while (lineStart > 0 && this.text[lineStart - 1] !== '\n') lineStart--;
	let indent = '';
	for (let i = lineStart; i < position && (this.text[i] === ' ' || this.text[i] === '\t'); i++) indent += this.text[i];
	return indent;
};

/**
 * Sets, inserts, or (with a null valueText) removes one member of the object at objectPath.
 * Returns whether anything actually changed, so callers can report real edits rather than saves.
 */
JsonDocument.prototype.setMember = function (objectPath, key, valueText) {
	const node = this.nodes.get(objectPath);
	if (!node || node.type !== 'object') throw new Error('No object at "' + objectPath + '"');
	const member = node.members.get(key);

	if (valueText === null) {
		if (!member) return false;
		if (node.memberList.length === 1) {
			this.splice(node.start + 1, node.end - 1, '');
		} else if (member.index > 0) {
			// Swallow the comma and whitespace that joined this member to the previous one.
			this.splice(node.memberList[member.index - 1].memberEnd, member.memberEnd, '');
		} else {
			// The first member owns the comma that follows it instead.
			this.splice(member.memberStart, node.memberList[1].memberStart, '');
		}
		return true;
	}

	if (member) {
		if (this.text.slice(member.valueStart, member.valueEnd) === valueText) return false;
		this.splice(member.valueStart, member.valueEnd, valueText);
		return true;
	}

	if (!node.memberList.length) {
		this.splice(node.start + 1, node.end - 1, ' ' + JSON.stringify(key) + ': ' + valueText + ' ');
		return true;
	}
	const last = node.memberList[node.memberList.length - 1];
	const separator = this.text.slice(last.separatorStart, last.memberStart);
	const lead = separator.indexOf('\n') >= 0 ? this.newline + this.indentAt(last.memberStart) : ' ';
	this.splice(last.memberEnd, last.memberEnd, ',' + lead + JSON.stringify(key) + ': ' + valueText);
	return true;
};

JsonDocument.prototype.changed = function () {
	return this.edits.length > 0;
};

JsonDocument.prototype.apply = function () {
	if (!this.edits.length) return this.text;

	const sorted = this.edits.slice().sort((a, b) => a.start - b.start || a.end - b.end);
	const merged = [];
	for (const edit of sorted) {
		const previous = merged[merged.length - 1];
		if (previous && edit.start < previous.end) {
			// Two removals of adjacent members can overlap by design; merging them is exactly right.
			// Anything else overlapping would be a bug, and silently picking a winner would hide it.
			if (previous.text === '' && edit.text === '') {
				previous.end = Math.max(previous.end, edit.end);
				continue;
			}
			throw new Error('Conflicting edits at offset ' + edit.start);
		}
		merged.push({ start: edit.start, end: edit.end, text: edit.text });
	}

	let out = '';
	let cursor = 0;
	for (const edit of merged) {
		out += this.text.slice(cursor, edit.start) + edit.text;
		cursor = edit.end;
	}
	return out + this.text.slice(cursor);
};

/** One-line JSON in the style the asset files use: spaces inside the braces, spaces after the colons. */
function compactJson(value) {
	if (value === null) return 'null';
	if (typeof value === 'number') return numberText(value);
	if (typeof value !== 'object') return JSON.stringify(value);
	if (Array.isArray(value)) {
		return value.length ? '[ ' + value.map(compactJson).join(', ') + ' ]' : '[]';
	}
	const keys = Object.keys(value);
	if (!keys.length) return '{}';
	return '{ ' + keys.map(k => JSON.stringify(k) + ': ' + compactJson(value[k])).join(', ') + ' }';
}

function round4(value) {
	const rounded = Math.round(value * 1e4) / 1e4;
	return rounded === 0 ? 0 : rounded;
}

function numberText(value) {
	return String(round4(value));
}

/**
 * Writes a vector one axis at a time when the field already exists, so a bone nudged along Y keeps the
 * original text of its X and Z, decimal points and all.
 */
function setVector(doc, ownerPath, key, values, keepZero) {
	const path = ownerPath + '/' + key;
	const node = doc.node(path);
	const isZero = !values[0] && !values[1] && !values[2];

	if (isZero && !keepZero) return node ? doc.setMember(ownerPath, key, null) : false;
	if (!node || node.type !== 'object') {
		return doc.setMember(ownerPath, key, compactJson({ X: values[0], Y: values[1], Z: values[2] }));
	}

	let changed = false;
	['X', 'Y', 'Z'].forEach((axis, index) => {
		if (round4(num(node.value[axis], 0)) === values[index]) return;
		if (doc.setMember(path, axis, numberText(values[index]))) changed = true;
	});
	return changed;
}

function setFlag(doc, ownerPath, key, enabled) {
	return doc.setMember(ownerPath, key, enabled ? 'true' : null);
}

function setNumber(doc, ownerPath, key, value, omitWhen) {
	return doc.setMember(ownerPath, key, value === omitWhen ? null : numberText(value));
}

/**
 * Pushes the scene back into the skeleton document. Offsets come from group origins, which accumulate
 * additively in Blockbench exactly as they do in the runtime, so the difference against the parent is
 * the bone's own offset.
 */
function collectSkeletonEdits(doc) {
	const defs = doc.value.Bones || [];
	const origins = new Map();
	const moved = [];

	const groupFor = name => Group.all.find(g => g.name === name);

	for (const def of defs) {
		const group = groupFor(String(def.Name));
		if (group) origins.set(String(def.Name), group.origin.slice());
	}

	defs.forEach((def, index) => {
		const name = String(def.Name);
		const group = groupFor(name);
		if (!group) return;

		const bonePath = '/Bones/' + index;
		const parentOrigin = def.Parent && origins.has(String(def.Parent)) ? origins.get(String(def.Parent)) : [0, 0, 0];
		const offset = [0, 1, 2].map(axis => round4(group.origin[axis] - parentOrigin[axis]));
		const rotation = groupRotationToBind(group.rotation).map(round4);

		// Offset is written even when zero because the file spells out zero offsets on the spine joints,
		// where their presence is the documented reason those bones exist at all.
		let touched = setVector(doc, bonePath, 'Offset', offset, 'Offset' in def);
		if (setVector(doc, bonePath, 'Rotation', rotation, false)) touched = true;
		if (touched) moved.push(name);

		const meta = group.titan_bone;
		if (meta && meta.dirty) {
			writeBoneMetadata(doc, bonePath, meta);
			meta.dirty = false;
		}
	});

	moved.push.apply(moved, collectSocketEdits(doc, origins));
	return moved;
}

/**
 * Sockets are stored relative to their bone, so the offset is the marker's pivot measured against the
 * bone's origin, which is the exact inverse of how the marker was placed. Moving the bone therefore
 * carries its sockets along without changing their numbers.
 *
 * Normal is only written for a marker the author actually turned, or one that already declared it.
 * Sliding a socket along the surface changes the direction the spawner would derive, and pinning that
 * as a Normal on every socket anyone nudged would bury the handful that genuinely need one.
 */
function collectSocketEdits(doc, origins) {
	const sockets = doc.value.WeakpointSockets || [];
	if (!sockets.length) return [];

	const groups = new Map();
	for (const group of Group.all) {
		if (group.titan_socket) groups.set(group.titan_socket.index, group);
	}

	const moved = [];
	sockets.forEach((socket, index) => {
		const group = groups.get(index);
		if (!group) return;

		const path = '/WeakpointSockets/' + index;
		const origin = origins.get(String(socket.Bone)) || [0, 0, 0];
		const offset = [0, 1, 2].map(axis => round4(group.origin[axis] - origin[axis]));
		let touched = setVector(doc, path, 'Offset', offset, true);

		const meta = group.titan_socket;
		const rotation = group.rotation.map(round4);
		const turned = meta.rotation ? rotation.some((angle, axis) => angle !== meta.rotation[axis]) : false;

		const normal = markerRotationToNormal(group.rotation).map(round4);
		const derived = (derivedNormal(offset) || [0, 1, 0]).map(round4);
		const wanted = (meta.declared || turned) && normal.some((n, axis) => n !== derived[axis]);

		if (wanted ? setVector(doc, path, 'Normal', normal, true) : doc.setMember(path, 'Normal', null)) {
			touched = true;
		}

		if (touched) moved.push('socket ' + index);
	});
	return moved;
}

function writeBoneMetadata(doc, bonePath, meta) {
	doc.setMember(bonePath, 'Prefab', meta.prefab ? JSON.stringify(meta.prefab) : null);
	setNumber(doc, bonePath, 'PrefabYaw', prefabYawSteps(meta.prefabYaw) * 90, 0);
	if (meta.pivot) setVector(doc, bonePath, 'Pivot', meta.pivot.map(round4), true);
	else doc.setMember(bonePath, 'Pivot', null);
	setNumber(doc, bonePath, 'Scale', round4(meta.scale), 1);
	setFlag(doc, bonePath, 'MirrorX', meta.mirrorX);
	setNumber(doc, bonePath, 'ColliderStride', meta.colliderStride || 0, 0);
	setFlag(doc, bonePath, 'ColliderAllFaces', meta.colliderAllFaces);
	setNumber(doc, bonePath, 'MaxParts', meta.maxParts || 0, 0);
	setFlag(doc, bonePath, 'Hollow', meta.hollow);
	doc.setMember(bonePath, 'Detachable', meta.detachable === false ? 'false' : null);
}

function saveSkeleton() {
	const session = Project && Project.titan;
	if (!session) return fail('Titan Rig', 'This project was not imported by Titan Rig.');

	try {
		const doc = new JsonDocument(session.skeletonRaw);
		const moved = collectSkeletonEdits(doc);
		if (!doc.changed()) return Blockbench.showQuickMessage('Skeleton unchanged', 2000);

		const text = doc.apply();
		// Re-parsing before writing turns a bug in the splicing into a refused save rather than a
		// corrupted skeleton.
		parseJson(text, session.skeletonPath);

		fs.writeFileSync(session.skeletonPath, text, 'utf8');
		session.skeletonRaw = text;

		Blockbench.showQuickMessage(moved.length
			? 'Saved skeleton (' + moved.join(', ') + ')'
			: 'Saved skeleton', 2500);
	} catch (err) {
		console.error(err);
		fail('Titan Rig', 'Could not save the skeleton:\n\n' + err.message);
	}
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

function settingValue(id) {
	const setting = settings[id];
	return setting ? String(setting.value || '').trim() : '';
}

function requireDesktop() {
	if (!isApp) {
		fail('Titan Rig', 'Titan Rig needs the desktop version of Blockbench to read the repo and the game assets.');
		return false;
	}
	return true;
}

function requireHytalePlugin() {
	if (!Formats.hytale_character) {
		fail('Titan Rig', 'The official "Hytale Models" plugin is not installed. Titan Rig builds its rig in that plugin\'s hytale_character format, and relies on it to export .blockyanim files.');
		return false;
	}
	return true;
}

function openImportDialog() {
	if (!requireDesktop() || !requireHytalePlugin()) return;

	const repoRoot = settingValue('titan_repo_root');
	const assetRoot = settingValue('titan_asset_root');

	if (!repoRoot || !fileExists(joinPath(repoRoot, REPO.skeletons))) {
		return fail('Titan Rig', 'Set "Titan Repo Root" in Settings > Edit to the root of the Titan mod (the folder containing build.gradle.kts).\n\nCurrently: ' + (repoRoot || '(unset)'));
	}
	if (!assetRoot) {
		return fail('Titan Rig', 'Set the Hytale asset root first, with File > Titan Rig Paths...\n\n' + EXTRACT_HINT);
	}

	const skeletons = listJsonNames(joinPath(repoRoot, REPO.skeletons));
	if (!skeletons.length) return fail('Titan Rig', 'No skeletons found in ' + REPO.skeletons);

	const variants = listJsonNames(joinPath(repoRoot, REPO.variants));
	const skeletonOptions = {};
	for (const name of skeletons) skeletonOptions[name] = name;
	const variantOptions = { '': 'None (use the skeleton\'s default prefabs)' };
	for (const name of variants) variantOptions[name] = name;

	const dialog = new Dialog({
		id: 'titan_import',
		title: 'Import Titan Rig',
		form: {
			skeleton: { label: 'Skeleton', type: 'select', options: skeletonOptions, value: skeletons[0] },
			variant: { label: 'Variant', type: 'select', options: variantOptions, value: '' },
			variant_note: { type: 'info', text: 'The variant only selects the rock-type prefab suffix. BodyScale is not applied: animation positions are authored in model units and the runtime scales the whole rig at its root.' }
		},
		onConfirm(result) {
			dialog.hide();
			runImport({
				repoRoot: repoRoot,
				assetRoot: assetRoot,
				skeletonId: result.skeleton,
				variantId: result.variant || null
			});
		}
	});
	dialog.show();
}

async function runImport(options) {
	Blockbench.showStatusMessage('Building titan rig...', 3000);
	try {
		const summary = await buildRig(options);
		let message = summary.bones + ' bones, ' + summary.cubes + ' voxels, ' + summary.clips + ' clips (' + summary.atlasCells + ')';
		if (summary.missingPrefabs.length) message += '\nMissing prefabs: ' + summary.missingPrefabs.join(', ');
		if (summary.missingBlocks.length) message += '\nUnknown blocks: ' + summary.missingBlocks.join(', ');
		Blockbench.showQuickMessage(summary.bones + ' bones, ' + summary.cubes + ' voxels, ' + summary.clips + ' clips', 3000);
		if (summary.missingPrefabs.length || summary.missingBlocks.length) {
			fail('Titan Rig - imported with gaps', message);
		}
	} catch (err) {
		console.error(err);
		fail('Titan Rig', 'Import failed:\n\n' + (err && err.message ? err.message : String(err)));
	}
}

function openBonePropertiesDialog() {
	const session = Project && Project.titan;
	if (!session) return fail('Titan Rig', 'This project was not imported by Titan Rig.');

	const group = Group.first_selected || (Group.selected && Group.selected[0]);
	if (!group || !group.titan_bone) {
		return fail('Titan Rig', 'Select a bone group in the outliner first.');
	}

	const meta = group.titan_bone;
	const dialog = new Dialog({
		id: 'titan_bone_properties',
		title: 'Titan Bone: ' + group.name,
		form: {
			info: { type: 'info', text: 'Offset and Rotation come from the group transform and are written on save. These are the fields Blockbench has no equivalent for.' },
			prefab: { label: 'Prefab', type: 'text', value: meta.prefab || '' },
			prefabYaw: {
				label: 'Prefab Yaw',
				type: 'select',
				value: String(prefabYawSteps(meta.prefabYaw) * 90),
				options: { 0: '0', 90: '90', 180: '180', 270: '270' }
			},
			pivot: { label: 'Pivot', type: 'vector', value: meta.pivot || [0, 0, 0], step: 0.1 },
			scale: { label: 'Scale', type: 'number', value: meta.scale, step: 0.05, min: 0.01 },
			mirrorX: { label: 'Mirror X', type: 'checkbox', value: meta.mirrorX },
			colliderStride: { label: 'Collider Stride', type: 'number', value: meta.def.ColliderStride || 0, step: 1, min: 0 },
			colliderAllFaces: { label: 'Collider All Faces', type: 'checkbox', value: meta.def.ColliderAllFaces === true },
			maxParts: { label: 'Max Parts', type: 'number', value: meta.def.MaxParts || 0, step: 1, min: 0 },
			hollow: { label: 'Hollow', type: 'checkbox', value: meta.def.Hollow === true },
			detachable: { label: 'Detachable', type: 'checkbox', value: meta.def.Detachable !== false },
			reimport: { type: 'info', text: 'Changing Prefab, Prefab Yaw, Pivot, Scale or Mirror X only affects the file. Re-import the rig to see the voxels move.' }
		},
		onConfirm(result) {
			meta.prefab = String(result.prefab || '').trim() || null;
			// A select hands back its key as a string, and num only takes numbers.
			meta.prefabYaw = num(Number(result.prefabYaw), 0);
			meta.pivot = (result.pivot[0] || result.pivot[1] || result.pivot[2]) ? result.pivot.slice() : null;
			meta.scale = num(result.scale, 1);
			meta.mirrorX = result.mirrorX === true;
			meta.colliderStride = num(result.colliderStride, 0);
			meta.colliderAllFaces = result.colliderAllFaces === true;
			meta.maxParts = num(result.maxParts, 0);
			meta.hollow = result.hollow === true;
			meta.detachable = result.detachable !== false;
			meta.dirty = true;
			dialog.hide();
			Blockbench.showQuickMessage('Bone updated - use "Save Titan Skeleton" to write it out', 2500);
		}
	});
	dialog.show();
}

function clipEntryFor(session, name) {
	const animations = session.clipSetDoc && session.clipSetDoc.Animations;
	return (animations && animations[name]) || null;
}

/** Runs an edit against the clip set on disk, with the same source-preserving splicing as the skeleton. */
function editClipSet(session, edit) {
	const doc = new JsonDocument(session.clipSetRaw);
	edit(doc);
	if (!doc.changed()) return false;

	const text = doc.apply();
	parseJson(text, session.clipSetPath);
	fs.writeFileSync(session.clipSetPath, text, 'utf8');
	session.clipSetRaw = text;
	session.clipSetDoc = parseJson(text, session.clipSetPath);
	return true;
}

function openClipSettingsDialog() {
	const session = Project && Project.titan;
	if (!session) return fail('Titan Rig', 'This project was not imported by Titan Rig.');
	if (!session.clipSetPath) return fail('Titan Rig', 'Skeleton "' + session.skeletonId + '" has no ClipSet.');

	const animation = BBAnimation.selected;
	if (!animation) return fail('Titan Rig', 'Select an animation first.');

	const name = animation.titan_clip || animation.name;
	const entry = clipEntryFor(session, name);
	if (!entry) {
		return fail('Titan Rig', 'Animation "' + name + '" is not registered in ' + session.clipSetId + '.json.\n\nUse "Register Titan Clip" to add it.');
	}

	const dialog = new Dialog({
		id: 'titan_clip_settings',
		title: 'Titan Clip: ' + name,
		form: {
			file: { label: 'File', type: 'info', text: String(entry.File || '') },
			looping: { label: 'Looping', type: 'checkbox', value: entry.Looping === true },
			speed: { label: 'Speed', type: 'number', value: num(entry.Speed, 1), step: 0.05, min: 0.01 },
			blend: { label: 'Blending Duration', type: 'number', value: num(entry.BlendingDuration, 0.2), step: 0.05, min: 0 },
			positionScale: { label: 'Position Scale', type: 'number', value: num(entry.PositionScale, 1), step: 0.001, min: 0 },
			flipFacing: { label: 'Flip Facing', type: 'checkbox', value: entry.FlipFacing === true },
			note: { type: 'info', text: 'Position Scale and Flip Facing exist for clips borrowed from another rig, such as the player character. Leave them at 1 and off for clips authored against this skeleton.' }
		},
		onConfirm(result) {
			try {
				const path = '/Animations/' + name;
				const written = editClipSet(session, doc => {
					doc.setMember(path, 'Looping', result.looping ? 'true' : 'false');
					setNumber(doc, path, 'Speed', num(result.speed, 1), 1);
					doc.setMember(path, 'BlendingDuration', numberText(num(result.blend, 0.2)));
					setNumber(doc, path, 'PositionScale', num(result.positionScale, 1), 1);
					setFlag(doc, path, 'FlipFacing', result.flipFacing === true);
				});
				dialog.hide();
				Blockbench.showQuickMessage(written ? 'Updated ' + session.clipSetId + '.json' : 'Clip unchanged', 2500);
			} catch (err) {
				console.error(err);
				fail('Titan Rig', 'Could not update the clip set:\n\n' + err.message);
			}
		}
	});
	dialog.show();
}

function openRegisterClipDialog() {
	const session = Project && Project.titan;
	if (!session) return fail('Titan Rig', 'This project was not imported by Titan Rig.');
	if (!session.clipSetPath) return fail('Titan Rig', 'Skeleton "' + session.skeletonId + '" has no ClipSet.');

	const animation = BBAnimation.selected;
	if (!animation) return fail('Titan Rig', 'Select an animation first.');

	// Sit new clips next to the ones already in the set rather than guessing a folder.
	const existing = Object.values(session.clipSetDoc.Animations || {}).map(e => String(e.File || ''));
	const sample = existing.find(file => file.indexOf('/') > 0) || 'Titan/Animations/Placeholder.blockyanim';
	const defaultFile = sample.slice(0, sample.lastIndexOf('/') + 1) + animation.name + '.blockyanim';

	const dialog = new Dialog({
		id: 'titan_register_clip',
		title: 'Register Titan Clip',
		form: {
			name: { label: 'Clip Name', type: 'text', value: animation.name },
			file: { label: 'File (under Common/)', type: 'text', value: defaultFile },
			looping: { label: 'Looping', type: 'checkbox', value: animation.loop === 'loop' },
			blend: { label: 'Blending Duration', type: 'number', value: 0.2, step: 0.05, min: 0 },
			note: { type: 'info', text: 'This registers the clip and points the animation at the file. Save the animation afterwards to write the .blockyanim itself.' }
		},
		onConfirm(result) {
			const name = String(result.name || '').trim();
			const file = String(result.file || '').trim().replace(/\\/g, '/');
			if (!name || !file) return;

			try {
				editClipSet(session, doc => {
					doc.setMember('/Animations', name, compactJson({
						File: file,
						Looping: result.looping === true,
						BlendingDuration: round4(num(result.blend, 0.2))
					}));
				});

				const target = joinPath(session.repoRoot, REPO.common, file.replace(/\//g, nodePath.sep));
				fs.mkdirSync(nodePath.dirname(target), { recursive: true });
				animation.name = name;
				animation.titan_clip = name;
				animation.path = target;
				animation.saved = false;

				dialog.hide();
				Blockbench.showQuickMessage('Registered "' + name + '" - save the animation to write ' + file, 3500);
			} catch (err) {
				console.error(err);
				fail('Titan Rig', 'Could not register the clip:\n\n' + err.message);
			}
		}
	});
	dialog.show();
}

function openPathsDialog() {
	const dialog = new Dialog({
		id: 'titan_paths',
		title: 'Titan Rig Paths',
		form: {
			repo: { label: 'Titan Repo Root', type: 'folder', value: settingValue('titan_repo_root') },
			assets: { label: 'Hytale Asset Root', type: 'folder', value: settingValue('titan_asset_root') },
			asset_note: {
				type: 'info',
				text: 'The asset root is the folder containing Common/ and Server/, so either a shared-source ' +
					'HytaleAssets folder or wherever you extracted Assets.zip. The archive itself cannot be read ' +
					'in place: Blockbench only lets a plugin read whole files, and it is about 3.5 GB.'
			}
		},
		onConfirm(result) {
			if (result.repo) settings.titan_repo_root.set(result.repo);
			if (result.assets) settings.titan_asset_root.set(result.assets);
			dialog.hide();
			Blockbench.showQuickMessage('Titan Rig paths updated', 2000);
		}
	});
	dialog.show();
}

// ---------------------------------------------------------------------------
// Registration
// ---------------------------------------------------------------------------

BBPlugin.register(PLUGIN_ID, {
	title: 'Titan Rig',
	author: 'Hexvane',
	description: 'Imports Titan skeletons with their prefab voxels for animation authoring, and writes skeleton edits back to the mod.',
	icon: 'precision_manufacturing',
	version: '1.0.0',
	min_version: '5.0.5',
	variant: 'desktop',
	tags: ['Hytale', 'Titan'],
	onload() {
		fs = requireNativeModule('fs');
		nodePath = requireNativeModule('path');

		track(new Setting('titan_repo_root', {
			name: 'Titan Repo Root',
			description: 'Root of the Titan mod checkout, the folder containing build.gradle.kts',
			category: 'edit',
			type: 'text',
			value: ''
		}));
		track(new Setting('titan_asset_root', {
			name: 'Hytale Asset Root',
			description: 'Extracted Hytale assets, the folder holding Common/ and Server/, used for block textures',
			category: 'edit',
			type: 'text',
			value: ''
		}));

		const importRig = track(new Action('titan_import_rig', {
				name: 'Import Titan Rig...',
				description: 'Build a bone rig with prefab voxels from a Titan skeleton',
				icon: 'precision_manufacturing',
				category: 'file',
				click: openImportDialog
			}));
		const saveSkeletonAction = track(new Action('titan_save_skeleton', {
				name: 'Save Titan Skeleton',
				description: 'Write bone offsets, rotations and properties back to the skeleton JSON',
				icon: 'save',
				category: 'file',
				condition: () => !!(Project && Project.titan),
				click: saveSkeleton
			}));
		const boneProperties = track(new Action('titan_bone_properties', {
				name: 'Titan Bone Properties...',
				description: 'Edit the prefab, pivot, scale and collider settings of the selected bone',
				icon: 'settings',
				category: 'edit',
				condition: () => !!(Project && Project.titan),
				click: openBonePropertiesDialog
			}));
		const clipSettings = track(new Action('titan_clip_settings', {
				name: 'Titan Clip Settings...',
				description: 'Edit the selected clip\'s entry in the skeleton\'s clip set',
				icon: 'tune',
				category: 'animation',
				condition: () => !!(Project && Project.titan && Project.titan.clipSetPath),
				click: openClipSettingsDialog
			}));
		const registerClip = track(new Action('titan_register_clip', {
				name: 'Register Titan Clip...',
				description: 'Add the selected animation to the clip set and point it at a file',
				icon: 'playlist_add',
				category: 'animation',
				condition: () => !!(Project && Project.titan && Project.titan.clipSetPath),
				click: openRegisterClipDialog
			}));
		const paths = track(new Action('titan_paths', {
			name: 'Titan Rig Paths...',
			description: 'Set the Titan repo root and the Hytale asset root',
			icon: 'folder_open',
			category: 'file',
			click: openPathsDialog
		}));

		// Blockbench builds a BarMenu's label but never mounts it, so a plugin cannot add a top-level
		// menu. These go into the existing ones, as the official Hytale plugin does, and every action is
		// reachable from the action search regardless.
		placeIn(MenuBar.menus.file, importRig);
		placeIn(MenuBar.menus.file, saveSkeletonAction);
		placeIn(MenuBar.menus.file, paths);
		placeIn(MenuBar.menus.animation, clipSettings);
		placeIn(MenuBar.menus.animation, registerClip);

		// Bone properties belongs on the bone itself, which is where you are when you want it.
		placeIn(Group.prototype.menu, boneProperties);
	},
	onunload() {
		while (placements.length) {
			const { menu, action } = placements.pop();
			try { menu.removeAction(action); } catch (err) { /* menu already torn down */ }
		}
		while (tracked.length) {
			const item = tracked.pop();
			try { item.delete(); } catch (err) { /* already gone */ }
		}
	}
});

})();
