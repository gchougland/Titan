# Titan gameplay trailer

`Titan_Gameplay_Trailer_1080p60.mp4` is the finished 80-second trailer: 1920 × 1080, 60 fps, H.264 video and stereo AAC audio. The MP4 is prepared for progressive playback.

The edit uses all six supplied recordings, with short gameplay selections, cuts on the music's beat grid, brief dissolves and directional transitions, animated logo bookends, and titles matching the CurseForge publishing assets. Baby Yaga and Baba Yaga are labeled as companions. The original recordings remain untouched.

Only `Crypt_Keeper_Battle.ogg` is used for audio. Its 40-second loop plays twice with a brief opening fade and a two-second ending fade. No gameplay sound or additional sound effects are mixed in.

| Trailer time | Section |
| --- | --- |
| 0:00–0:05 | Animated Titan logo over the Roaming Temple reveal |
| 0:05–0:15 | Stone Talus: approach, climb, weakpoint attacks and fall |
| 0:15–0:27.5 | Roaming Temple: stomps, beam attack and weakpoint combat |
| 0:27.5–0:32.5 | Baby Yaga: transformation and walking companion |
| 0:32.5–0:37.5 | Baba Yaga: transformation into the larger house |
| 0:37.5–0:52.5 | Dunewyrm: scale reveal, poison dodge and collapse |
| 0:52.5–1:10 | Crypt Keeper: emergence, sweeps and summoned minions |
| 1:10–1:15 | Four fast combat shots |
| 1:15–1:20 | Animated logo, Think Bigger, and Hexvane credit |

`edit.json` contains the exact source timecodes and durations. Section transitions use a quarter-second outgoing handle, so they do not shorten this timeline. The Dunewyrm recording is centered and cropped slightly to fit 16:9. Yaga title panels cover transient gameplay notifications during their transformations.

## Rebuilding

Run `render_trailer.py` with Python. It resolves FFmpeg from the local `.deps` directory, `FFMPEG_EXE`, or PATH. The default encoder uses NVIDIA NVENC; pass `--cpu` to use libx264 instead. The source directory and asset paths are in `edit.json`.

- `--section N` renders one section, numbered 0–8.
- `--merge-only` assembles already rendered sections.
- `--resume` reuses existing section renders. Rerender any section whose timecodes or graphics you changed before using this option.

`graphics/build_graphics.py` recreates the title PNGs with Pillow and the Windows Arial fonts. `verify_trailer.py` checks duration, frame count, resolution and audio correlation against the music track, and creates frame contact sheets plus signal-analysis logs in `review/final`.

Generated working media, review frames, tool dependencies and the large final MP4 are ignored by Git. The finished file remains available locally in this directory.
