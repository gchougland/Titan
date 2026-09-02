# Titan

A Hytale mod by Hexvane.

Titans are enormous creatures built out of thousands of real blocks. They are too big to trade blows with
and their bodies are solid, so fighting one is really a climb: get onto the thing, stay on it, and break
whatever it keeps up there before it shakes you off.

This mod is a work in progress. The Stone Talus is the first titan in it, and more will be added over time.

## The Stone Talus

A Talus sleeps in the open, curled up and still, and looks like a rock formation until you get within a few
blocks of it, at which point it stands up.

The ore growing out of its back is the only part of it that can be hurt. Break every node and the whole
thing comes apart, scattering a mining trip's worth of ore across the ground.

Six kinds exist, each cut from a different rock and each tougher than the last. Copper and iron turn up in
the zone 1 plains and forests, thorium in the zone 2 deserts, cobalt on the zone 3 ice, and adamantite out
in the zone 4 wastes. Mithril is out there too, but you will walk a long way before you see one.

## Fighting one

A Talus has five attacks, and each one is also an opening. Each is announced before it lands: an amber ring
burns onto the ground where the blow is going to fall and fills in as the windup runs out, so nothing it
does should catch you twice.

When it smashes down with an arm, the hand sticks in the ground for a moment and becomes a step. Run up the
forearm to get onto its back.

When it slams its whole body forward it lies there winded for several seconds, back low and completely
exposed. This is the best opening in the fight.

When it raises both fists and drives them in together, the ring is enormous and the blow itself is almost
harmless: it throws you straight up, and the landing does the damage. Both arms end up buried, giving two
ramps at once and the widest way up the titan offers.

Back away and it stops walking after you. It reaches down instead, tears a boulder out of the earth in a
burst of dust and cracked ground, and lobs it on a slow high arc, with the spot it is going to land on
ringed the whole way down. The arc leaves enough time to walk out of the ring, but only if you move at once.

Get on its back and it may answer by planting the front edge of its slab into the dirt and driving forward
like a plough, arms flung out behind it, tearing a corridor through everything in front. Anyone still
standing on it goes over the front. It does this rarely and it will not do it twice in a row, and the run
ends with the titan beached face-down for a long stretch, which anyone who climbs back on can exploit.

Everything except the boulder hits hard and throws you a long way, so underneath it and behind it are the
safe places. The legs are far enough apart to run between.

## Worth knowing

No two are built the same. Each grows between two and four ore nodes, and where they sit is different every
time, so some are an easy climb and some are not.

Titans do not wander. One has claimed its patch of ground and will be standing there whenever you come
back. Kill it and the spot stays empty for a while before another moves in. Wound it and walk away, though,
and it will be whole again next time.

## Commands

`/titan spawn <variant>` places one in front of you, `/titan list` shows what is nearby, and `/titan kill`
clears them out. `/ti` works as a shorthand.

`/titan dance` plays a stock player emote on the nearest titan and has no effect on the fight.

`/titan perf` reports how much of last tick a titan spent talking to your client, and how much of it was
skipped rather than sent. It is the first thing to check if a very large titan looks like it is coming apart
as it walks.

## Server settings

`mods/Hexvane_Titan/config.json` holds a handful of switches. `WeakpointHealthMultiplier`,
`AttackDamageMultiplier`, `AttackKnockbackMultiplier`, `PickaxeDamageMultiplier` and `MaceDamageMultiplier`
scale the whole ladder at once, and `DisabledVariants` keeps named variants out of the world.

`BattleMusic` decides whether a fight takes over the music for everyone in it, and `Telegraphs` whether the
ground markers are drawn at all. Both default to on. Turning telegraphs off makes the fight considerably
harder rather than merely quieter, since the boulder's landing spot and the plough's corridor are the only
warning either one gives.

The rest are about what a titan costs to watch. A titan is thousands of block entities and each one that
moves is a packet, so a big one walking can say more per tick than a connection can carry, which arrives
looking like parts of it flickering. `PartSyncEpsilon` is how far a block may drift, in blocks, before the
server bothers to correct you. The default of a tenth is not visible on something tens of blocks tall, and
it keeps most of the body out of most ticks. `PartSyncRotationEpsilon` is the same for a block's own facing,
in degrees. Setting either to zero sends every update unconditionally.

`PartSyncInterval` puts a floor on the gap between one block's updates, in seconds, and is off by default
because unlike the tolerances it drops updates that did have something to say. `0.1` halves the traffic.

`ParallelPartSync` moves the work of posing a titan off the world thread and across the tick pool. Off by
default, and only worth reaching for if a titan is costing you tick time rather than bandwidth.

`EntityLodRatio` overrides how aggressively the server stops sending you small entities at range. Titans
are built from single blocks, which the default gives up on at about 169 blocks, close enough that a titan
on the horizon comes apart while its shape is still readable. `0.000015` reaches past the default view
distance. It applies to every small entity though, dropped items included, so it costs bandwidth to set.

Individual variants can be retuned in their own files under `Server/Titan/Variants`. Every number the three
new attacks use is there (chance, reach, damage, radius, timing), along with the particle system and sound
for each telegraph and the music track the fight plays. Emptying any of those strings turns off that one
piece for that one variant.

## Installation

Drop the mod into your server's mods folder and restart. Titans start appearing on their own, in existing
worlds as well as new ones.

## License

All rights reserved. See [LICENSE](LICENSE).
