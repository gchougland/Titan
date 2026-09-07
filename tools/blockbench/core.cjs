/**
 * Pure Titan Rig helpers shared by the Blockbench plugin and the Node harness.
 * No Blockbench or THREE dependency.
 */
'use strict';

function num(value, fallback) {
	return typeof value === 'number' && isFinite(value) ? value : fallback;
}

function vec(source, fallback) {
	const f = fallback || 0;
	if (!source) return [f, f, f];
	return [num(source.X, f), num(source.Y, f), num(source.Z, f)];
}

function round4(value) {
	const rounded = Math.round(value * 1e4) / 1e4;
	return rounded === 0 ? 0 : rounded;
}

function numberText(value) {
	return String(round4(value));
}

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

function prefabYawSteps(degrees) {
	const d = ((Math.round(num(degrees, 0) / 90) % 4) + 4) % 4;
	return d;
}

function vectorObject(values) {
	return { X: round4(values[0]), Y: round4(values[1]), Z: round4(values[2]) };
}

/**
 * Resolves bind offsets into absolute origins and IK role tags.
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
		shell: def.Shell === true,
		collider: def.Collider !== false,
		usable: def.Usable === true,
		useHint: def.UseHint ? String(def.UseHint) : null,
		def: def
	}));

	const byName = new Map();
	for (const bone of bones) byName.set(bone.name, bone);

	for (const bone of bones) {
		const parent = bone.parent ? byName.get(bone.parent) : null;
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

/**
 * Validates parent pointers: unique names, known parents, no cycles.
 * @returns {string[]} error messages (empty if ok)
 */
function validateBoneTree(bones) {
	const errors = [];
	const byName = new Map();
	for (const bone of bones) {
		const name = String(bone.Name || bone.name || '');
		if (!name) {
			errors.push('A bone is missing a Name');
			continue;
		}
		if (byName.has(name)) errors.push('Duplicate bone name "' + name + '"');
		byName.set(name, bone);
	}
	for (const bone of bones) {
		const name = String(bone.Name || bone.name || '');
		const parent = bone.Parent != null ? bone.Parent : bone.parent;
		if (!parent) continue;
		const parentName = String(parent);
		if (!byName.has(parentName)) {
			errors.push('Bone "' + name + '" references missing parent "' + parentName + '"');
			continue;
		}
		const seen = new Set();
		let cursor = parentName;
		while (cursor) {
			if (cursor === name) {
				errors.push('Bone "' + name + '" is in a parent cycle');
				break;
			}
			if (seen.has(cursor)) break;
			seen.add(cursor);
			const next = byName.get(cursor);
			cursor = next ? (next.Parent != null ? String(next.Parent) : (next.parent ? String(next.parent) : null)) : null;
		}
	}
	return errors;
}

function prettyJson(value, newline) {
	const nl = newline || '\n';
	return JSON.stringify(value, null, '\t').replace(/\n/g, nl) + nl;
}

function emitNewSkeleton(options) {
	const id = String(options.id || 'New_Titan');
	const rootName = String(options.rootBone || 'Root');
	const hip = num(options.hipHeight, 0);
	const doc = {
		BodyBone: rootName,
		UnitScale: num(options.unitScale, 1),
		HipHeight: hip,
		ColliderConfig: options.colliderConfig || 'Titan_Platform',
		Bones: [
			{
				Name: rootName,
				Offset: vectorObject([0, hip, 0]),
				Detachable: false
			}
		],
		IkChains: [],
		WeakpointSockets: []
	};
	if (options.clipSet) doc.ClipSet = String(options.clipSet);
	return { id: id, doc: doc, text: prettyJson(doc) };
}

function emitVariantStub(options) {
	const id = String(options.id || 'New_Variant');
	const doc = {
		Skeleton: String(options.skeleton || id),
		DisplayName: String(options.displayName || id.replace(/_/g, ' ')),
		BodyScale: num(options.bodyScale, 1),
		RockType: options.rockType ? String(options.rockType) : undefined,
		WeakpointModel: options.weakpointModel ? String(options.weakpointModel) : undefined,
		WeakpointScale: num(options.weakpointScale, 1),
		WeakpointCountMin: num(options.weakpointCountMin, 0),
		WeakpointCountMax: num(options.weakpointCountMax, 0),
		WeakpointsToKill: num(options.weakpointsToKill, 0),
		WeakpointEmbed: num(options.weakpointEmbed, 0),
		WeakpointHealth: num(options.weakpointHealth, 100),
		ShellHealth: options.shellHealth != null ? num(options.shellHealth, 0) : undefined,
		SpawnFootprintRadius: num(options.spawnFootprintRadius, 4),
		SpawnFootprintRelief: num(options.spawnFootprintRelief, 2),
		SpawnHeadroom: num(options.spawnHeadroom, 12),
		MoveSpeed: num(options.moveSpeed, 3),
		TurnSpeed: num(options.turnSpeed, 60),
		WakeRadius: num(options.wakeRadius, 40),
		LoseTargetRadius: num(options.loseTargetRadius, 60),
		LeashRadius: num(options.leashRadius, 80)
	};
	for (const key of Object.keys(doc)) {
		if (doc[key] === undefined) delete doc[key];
	}
	return { id: id, doc: doc, text: prettyJson(doc) };
}

function boneDefFromMeta(meta, name, parent) {
	const def = { Name: name };
	if (parent) def.Parent = parent;
	def.Offset = vectorObject(meta.offset || [0, 0, 0]);
	const rot = meta.rotation || [0, 0, 0];
	if (rot[0] || rot[1] || rot[2]) def.Rotation = vectorObject(rot);
	if (meta.prefab) def.Prefab = meta.prefab;
	const yaw = prefabYawSteps(meta.prefabYaw) * 90;
	if (yaw) def.PrefabYaw = yaw;
	if (meta.pivot) def.Pivot = vectorObject(meta.pivot);
	if (meta.scale != null && meta.scale !== 1) def.Scale = round4(meta.scale);
	if (meta.mirrorX) def.MirrorX = true;
	if (meta.shell) def.Shell = true;
	if (meta.collider === false) def.Collider = false;
	if (meta.usable) def.Usable = true;
	if (meta.useHint) def.UseHint = meta.useHint;
	if (meta.colliderStride) def.ColliderStride = meta.colliderStride | 0;
	if (meta.colliderAllFaces) def.ColliderAllFaces = true;
	if (meta.maxParts) def.MaxParts = meta.maxParts | 0;
	if (meta.hollow) def.Hollow = true;
	if (meta.detachable === false) def.Detachable = false;
	if (meta.sliceMinY != null) def.SliceMinY = meta.sliceMinY | 0;
	if (meta.sliceMaxY != null) def.SliceMaxY = meta.sliceMaxY | 0;
	return def;
}

/**
 * Rebuilds a full skeleton object from session bone metas + sockets + ik + globals.
 * Used when structural CRUD makes comment-preserving splice impractical.
 */
function assembleSkeletonDoc(session) {
	const bones = (session.bones || []).map(meta => boneDefFromMeta(meta, meta.name, meta.parent));
	const errors = validateBoneTree(bones);
	if (errors.length) throw new Error(errors.join('\n'));

	const doc = {
		BodyBone: session.bodyBone || (bones[0] && bones[0].Name) || 'Root',
		UnitScale: num(session.unitScale, 1),
		HipHeight: num(session.hipHeight, 0),
		ColliderConfig: session.colliderConfig || 'Titan_Platform',
		Bones: bones,
		IkChains: session.ikChains || [],
		WeakpointSockets: session.sockets || []
	};
	if (session.clipSet) doc.ClipSet = session.clipSet;
	if (session.proceduralWobble && session.proceduralWobble.length) {
		doc.ProceduralWobble = session.proceduralWobble;
	}
	if (session.animationPositionScale != null && session.animationPositionScale !== 1) {
		doc.AnimationPositionScale = session.animationPositionScale;
	}
	return doc;
}

// ---------------------------------------------------------------------------
// JSON write-back (comment-preserving splice)
// ---------------------------------------------------------------------------

function JsonDocument(text) {
	this.text = String(text).replace(/^\uFEFF/, '');
	this.newline = this.text.indexOf('\r\n') >= 0 ? '\r\n' : '\n';
	this.edits = [];
	this.nodes = new Map();
	this.value = this.parseDocument();
}

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

JsonDocument.prototype.indentAt = function (position) {
	let lineStart = position;
	while (lineStart > 0 && this.text[lineStart - 1] !== '\n') lineStart--;
	let indent = '';
	for (let i = lineStart; i < position && (this.text[i] === ' ' || this.text[i] === '\t'); i++) indent += this.text[i];
	return indent;
};

JsonDocument.prototype.setMember = function (objectPath, key, valueText) {
	const node = this.nodes.get(objectPath);
	if (!node || node.type !== 'object') throw new Error('No object at "' + objectPath + '"');
	const member = node.members.get(key);

	if (valueText === null) {
		if (!member) return false;
		if (node.memberList.length === 1) {
			this.splice(node.start + 1, node.end - 1, '');
		} else if (member.index > 0) {
			this.splice(node.memberList[member.index - 1].memberEnd, member.memberEnd, '');
		} else {
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

module.exports = {
	num: num,
	vec: vec,
	round4: round4,
	numberText: numberText,
	compactJson: compactJson,
	prefabYawSteps: prefabYawSteps,
	vectorObject: vectorObject,
	buildBoneTable: buildBoneTable,
	validateBoneTree: validateBoneTree,
	prettyJson: prettyJson,
	emitNewSkeleton: emitNewSkeleton,
	emitVariantStub: emitVariantStub,
	boneDefFromMeta: boneDefFromMeta,
	assembleSkeletonDoc: assembleSkeletonDoc,
	JsonDocument: JsonDocument,
	setVector: setVector,
	setFlag: setFlag,
	setNumber: setNumber
};
