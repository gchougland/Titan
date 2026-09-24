# Titan trailer graphics

All overlays are 1920 x 1080 transparent PNG files, ready to composite at x=0, y=0 over a 1080p trailer. Rendered at double resolution and downsampled for smooth type and edges. `build_graphics.py` reproduces every asset with Pillow and Windows Arial fonts.

- Six `title_*.png` files: lower-left title panels with neutral `MEET THE TITANS` eyebrow, ivory Arial Bold names, sage edge rule, and a translucent charcoal panel with a slate diagonal end. Opaque content stays within x=80..approximately 760 and y=784..916; precise bounds are in `graphics_manifest.json`.
- `end_text.png`: centered `THINK BIGGER.` at y=736 and `A HYTALE MOD BY HEXVANE` at y=868, leaving the center/top area clear for the supplied logo.
- `intro_tagline.png`: a smaller centered `THINK BIGGER.` at y=746, leaving room for an opening logo animation.
- `title_contact_sheet.jpg`: inspection preview only, not an overlay.

The palette and Arial type come directly from `publishing/curseforge/assets/00-titan.svg` and `01-titans.svg`: charcoal #192127, slate #263239, ivory #F1EFE5, sage #C1D59B. Geometry echoes their vertical accent and diagonal panel.

Names are verified against the CurseForge description and the source variant DisplayName fields, including exact `Baby Yaga` and `Baba Yaga` names. No invented traits, release dates, calls to purchase, or boss classification are added. The Yaga forms are companions, so they share neutral creature section labeling.

Suggested usage: fade each title in over 0.25 s, hold approximately 2 seconds, and fade out over 0.3 s. These are transparent assets, so the editor can animate position or opacity without rerendering the artwork.
