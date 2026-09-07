# Titan Rig — Blockbench plugin

Loads a Titan skeleton into Blockbench as a bone rig, fills each bone with the voxels of its prefab
using real Hytale block textures, and imports the skeleton's clip set as editable animations. You can
**create** skeletons and variants here, attach prefabs to bones with a live preview rebuild, edit
sockets and IK chains, then write everything back into the mod's JSON.

![Stone Talus imported into Blockbench](docs/stone_talus.png)

## Requirements

- Blockbench 5.0.5 or newer, **desktop** app. The plugin reads files from disk, so the web version
  cannot run it.
- The official **Hytale** Blockbench plugin, which provides the `hytale_character` format and the
  `.blockyanim` codec this plugin builds on. Install it from Blockbench's plugin store first.
- An extracted copy of the Hytale assets, see below.
- `tools/blockbench/core.cjs` must sit next to `titan_rig.js` (it ships in the repo). The plugin loads
  it from the Titan repo root via `fs` (not Node `require`, which Blockbench's sandbox does not allow
  for arbitrary files).

## Install

Blockbench: **File > Plugins > Load Plugin from File**, and pick `tools/blockbench/titan_rig.js`.

Blockbench remembers the file, so this survives a restart. Re-run the same menu item after pulling
changes to the plugin.

## Configure the two paths

Open **File > Titan Rig Paths...** and set both. They are also plain settings under
**Settings > Edit**, if you would rather type them.

**Titan Repo Root** — the root of this checkout, the folder holding `build.gradle.kts`. Everything
the plugin reads and writes in the mod is resolved from here:

| What | Where |
| --- | --- |
| Skeletons | `src/main/resources/Server/Titan/Skeletons` |
| Variants | `src/main/resources/Server/Titan/Variants` |
| Clip sets | `src/main/resources/Server/Titan/Clips` |
| Prefabs | `src/main/resources/Server/Prefabs` |
| Animations | `src/main/resources/Common` |

**Hytale Asset Root** — a folder containing `Common/` and `Server/`, used only for block textures.
Either the `HytaleAssets` folder of a shared-source checkout, or wherever you extracted `Assets.zip`.
The plugin also accepts the folder one level above either of those and finds the right one.

`Assets.zip` itself will not work, and the plugin says so rather than failing obscurely. Blockbench
hands plugins a restricted filesystem with no way to seek inside a file, only to read whole ones, and
the archive is about 3.5 GB. Extract it once:

```
%appdata%\Hytale\install\release\package\game\latest\Assets.zip
```

This is the same reason the official Hytale Avatar Loader asks for an extracted folder.

## Create a new titan (no hand-written JSON)

1. **File > Create Titan Skeleton...** — picks an id, root bone, hip height, and optional clip set.
   Writes `Server/Titan/Skeletons/<id>.json` and opens the rig.
2. **Add Element → Titan Bone** (toolbar dropdown next to Cube/Group), or right-click a bone →
   **Titan Bone** — grow the hierarchy. Drag groups to place bind offsets; rotations on the group
   become bind rotations on save.
3. **Edit > Attach Prefab...** — pick a prefab under `Server/Prefabs`, set yaw, optionally reset the
   pivot from the prefab bounds. Voxels rebuild in the scene immediately.
4. Tune **Titan Bone Properties...** for slices, hollow, shell, colliders, etc. Check **Rebuild
   preview now** to see the change without re-importing.
5. Optionally **Add Weakpoint Socket**, **Titan Skeleton Globals...**, **Titan IK Chains...**.
6. **File > Save Titan Skeleton** — writes the JSON. Structural edits (add/remove bone, sockets, IK
   list) do a full rewrite; transform-only edits splice the existing file so `$Comment` blocks survive.
7. **File > Create Titan Variant...** — writes a spawnable stub under `Server/Titan/Variants` pointing
   at the skeleton. Combat chances, drops, and fixtures stay hand-edited (or later dialogs).

Prefab **voxel sculpting** is still done in Hytale's prefab tools. This plugin attaches existing
prefabs to bones; it does not author `.prefab.json` block lists.

Behavior / AI remains Java. Dunewyrm-style procedural titans are out of scope (stub skeleton only).

## Import an existing rig

**File > Import Titan Rig...**, pick a skeleton and optionally a variant.

The variant only chooses the rock-type suffix on each prefab name, so `Stone_Talus_Iron` loads the
iron prefabs. `BodyScale` is deliberately not applied: animation positions are authored in model
units and the runtime scales the whole rig at its root, so applying it here would make every
position you author wrong by that factor.

What you get:

- One group per bone, parented as the skeleton defines, with the bind rotation on the group.
- The bone's prefab as one cube per voxel, UV-mapped into a generated atlas of the real block
  textures. Blocks that draw as models rather than cubes, so slabs, vines and rubble, become a flat
  cell of their computed colour, since there is no cube texture to sample.
- Every clip in the skeleton's clip set as a Blockbench animation, at 60 fps snapping, with each
  animation's `path` pointing back at its `.blockyanim` so Blockbench's own save writes it in place.
- A **Weakpoint Sockets** group, one entry per `WeakpointSockets` socket, each wearing the ore the
  variant's `WeakpointModel` renders. These are editable, see below.
- A **Titan Guides** group holding the IK rest targets (visible). Drag a rest marker and Save writes
  `RestOffset` relative to `BodyBone`. Bones on an IK chain with `Role: Foot` / `Hand` are tinted in
  the outliner, because the runtime solver overrides whatever you keyframe on them.

One model unit is one prefab block, and one Blockbench unit is one model unit, so the numbers in the
sidebar are the numbers in the JSON.

## Author animations

Work in Animate mode as usual. **Ctrl+S** saves the selected animation through the Hytale plugin's
`.blockyanim` exporter, straight back to the file it came from.

Two extra items live in the **Animation** menu:

- **Titan Clip Settings...** edits the selected clip's entry in the skeleton's clip set: looping,
  speed, blending duration, position scale, flip facing.
- **Register Titan Clip...** adds a new animation to the clip set and points it at a file, for clips
  you create in Blockbench rather than import.

Keyframe rotations are Euler degrees in the sidebar and quaternions in the file; the conversion is
handled on both sides. `smooth` interpolation maps to Blockbench's `catmullrom`.

## Edit the skeleton

Drag a bone's pivot to move it. **File > Save Titan Skeleton** derives `Offset` and `Rotation` from
the group transforms and writes them back.

**Menu / outliner actions:**

| Action | Purpose |
| --- | --- |
| Titan Bone Properties... | Prefab, yaw, pivot, scale, slices, shell, colliders, hollow |
| Attach Prefab... | Prefab picker + live voxel rebuild |
| Rebuild Bone Preview | Rebuild cubes for the selected bone |
| Titan Bone (Add Element dropdown) | Add a bone under the selection (also on bone context menu) |
| Rename / Reparent / Delete Titan Bone | Hierarchy edits under Edit and bone context menu |
| Add / Delete Weakpoint Socket | Socket CRUD (drag/rotate still writes Offset/Normal) |
| Titan Skeleton Globals... | BodyBone, HipHeight, UnitScale, ClipSet, ColliderConfig |
| Titan IK Chains... | Add/edit/remove chains (Kind, Role, bones, gait fields) |

`PrefabYaw` turns a bone's blocks about Y as they are read, for a prefab that was built facing a
different way from the one the rig expects: the runtime's forward is `-Z`, so a prefab built facing east
needs `90`. The pivot is expressed in the turned coordinates.

### Weakpoint sockets

![Ore nodes standing out from the body surface](docs/weakpoint_sockets.png)

Each socket is a group holding the ore that socket would carry in game, read from the variant's
`WeakpointModel`: its `.blockymodel` boxes, its texture, its `WeakpointScale`, and the sink the spawner
applies so the node's centre, not its origin, lands on the socket. What you see is what will spawn.

Every socket wears its ore, but only as many start visible as the variant actually rolls,
`WeakpointCountMax`. Showing all of them at once buries the body under ore no single titan carries, so
the rest are hidden and one outliner click away.

**Position.** Drag a socket group and the save writes its new `Offset`. The offset is measured against
the bone the socket hangs off.

**Facing.** Rotate a socket group and the save writes a `Normal`. Left alone, the spawner aims each node
straight out from the bone pivot. Turn one back to the derived direction and the field is removed again.

**Add / Delete** from the Edit menu or bone context menu. After adding sockets, Save (full rewrite) then
re-import if you want ore meshes instead of spike placeholders on brand-new sockets.

Transform-only write-back splices the original text rather than reprinting the JSON, so `$Comment`
blocks, blank lines, inline vectors and trailing `.0` on whole numbers all survive. Structural CRUD
rewrites the file cleanly (comments on that file are not preserved across those saves).

### IK chains

**Titan IK Chains...** edits the `IkChains` list (names, bone sequences, Role, pole, stride, phase).
Rest targets in **Titan Guides** are movable; Save writes `RestOffset` from the marker position relative
to `BodyBone`. After list edits, Save and re-import to refresh guide markers. Verify gait in-game with
`/titan debug ik`.

## Tests

```
cd tools/blockbench
node harness.mjs
```

Exercises `core.cjs`: bone table origins, tree validation, skeleton/variant emitters, assemble, and
comment-preserving JSON splice against real mod skeletons (Yaga_Egg, Roaming_Temple). No Blockbench
required.
