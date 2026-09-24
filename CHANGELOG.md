# Changelog

## [1.0.1] - 9/22/2026

### Added

- **Performance command** Added server memory readings and visible titan piece counts to `/titan perf` and its log output.
- **Roaming Temple tree clearing** Added an optional setting for temples to clear trees as they walk. Disabled by default.
- **Yaga swimming** Baba Yaga and Baby Yaga float in deep water with their floors just above the surface, paddle their legs, and keep following you or the wand.

### Changed

- **Roaming Temple spawns** Temples appear less often in the plains.
- **Titan models** Titans use fewer visible pieces while keeping their shape, climbable surfaces, and usable furniture. Combined parts break back into blocks when they die.
- **Endless Leveling** Reduced the extra health titans receive at higher levels.
- **Yaga movement** Baba Yaga and Baby Yaga move faster when following players or the wand.
- **Baba Yaga chests** Both chests now have four rows. Extra items from older chests remain safe and become available as you make room.
- **Poison clouds** Lingering poison forms small green clouds that rise and fade.
- **Dunewyrm movement** Dunewyrms move around obstacles, back away from trees without breaking them, and return to the surface if buried.
- **Dunewyrm tongue** Raised the tongue by one block.

### Fixed

- **Titan footing** Titans no longer use trees as footholds.
- **Health bars** Fixed a crash while updating titan health bars across worlds.
- **Animations and textures** Fixed missing Crypt Keeper and rolling boulder animations and invalid particle textures.
- **Yaga legs** Legs keep facing the right way as the house walks.
- **Attachment models** Removed a client warning about a missing attachment model.
- **Poison duration** Repeated exposure no longer adds minutes of poison.
- **Arrow hits** Arrows stop against titan bodies instead of passing through to hidden weakpoints.
- **Crypt Keeper staff** Projectiles can hit Dunewyrm body segments.
- **Sand particles** Fixed a missing sand particle warning.
