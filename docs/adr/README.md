# Architecture decision records

Each ADR records one decision: the problem, what was chosen and why, and what it costs. They're
the project's memory. Read the ones for the area you're changing before you change it, and add one
when you make a decision someone would otherwise have to rediscover.

## Writing one

- Copy the header of a recent ADR: `- Status`, `- Date`, and `- Builds on` when it extends
  earlier ones. Then **Context**, **Decision**, **Consequences**, and measurements when
  there are any.
- Number it after the last one, and add it to the table below.
- Status is `accepted` once the change is merged. When a later ADR replaces part of one, say so in
  its status line ("superseded in part by ADR NNNN") rather than editing the old decision.
- Write for someone who wasn't there. Measurements say where they were taken (the machine names in
  [`docs/benchmark.md`](../benchmark.md)).

## Index

| ADR | Decision |
| --- | --- |
| [0001](0001-patch-only-repo.md) | The repo is patch-only, built on the Spigot/Paper model |
| [0002](0002-license-gpl3-with-minecraft-exception.md) | GPL-3.0-or-later with a Minecraft additional permission, and DCO sign-off |
| [0003](0003-module-boundaries-and-quality-gates.md) | Enforced module boundaries and one set of quality gates |
| [0004](0004-vendor-tree-builds-from-source.md) | The game is built from the patched source tree, starting with decompile fixes |
| [0005](0005-platform-layer.md) | The platform layer: whole-class replacements over Platform interfaces |
| [0006](0006-browser-build-link-step.md) | How the browser build links: removals by rule, a class library for the rest |
| [0007](0007-webgl2-backend-and-build-time-shaders.md) | The WebGL2 backend, under Mojang's frontend, with shaders translated at build time |
| [0008](0008-off-heap-memory-in-linear-memory.md) | Off-heap memory in the module's linear memory, and Netty in heap mode |
| [0009](0009-websocket-transport.md) | A WebSocket transport under the game's Connection, one packet per message |
| [0010](0010-browser-input.md) | Browser input: focus, a text input element, an asynchronous clipboard, dropped files |
| [0011](0011-storage.md) | Storage: the file system in memory, written behind to OPFS by a worker |
| [0012](0012-webgl2-backend-complete-and-screenshot-diffs.md) | Every pipeline in WebGL2, and screenshot diffs against the desktop |
| [0013](0013-web-audio.md) | Sound over Web Audio, with the browser's decoder and the desktop's panning |
| [0014](0014-loader-and-packaging.md) | The loader page, the build manifest, the service worker and the asset pack |
| [0015](0015-background-work-between-frames.md) | The background pool runs between frames, and the render thread's waits are counted |
| [0016](0016-eagler-handshake-in-the-client.md) | The Eagler handshake in the client, and Eagler skins |
| [0017](0017-via-in-the-client.md) | Via in the client, and choosing a server's version |
| [0018](0018-auto-finds-the-version-by-joining.md) | "Auto" finds a server's version by joining |
| [0019](0019-eagler-extras.md) | Eagler's extras: passwords, cookies, redirects, voice chat and shared worlds |
| [0020](0020-legacy-behaviour-layer.md) | The legacy behaviour layer: playing 1.8 servers as 1.8 did |
| [0021](0021-profiler-and-benchmark.md) | The profiler, the benchmark and the performance gate |
| [0022](0022-terrain-multi-draw.md) | Terrain in a few multi-draws a frame |
| [0023](0023-worker-mesher.md) | Terrain meshed in Web Workers by Incari's own mesher |
| [0024](0024-entities-particles-text.md) | Entities, particles and text |
| [0025](0025-memory-diet.md) | The memory diet |
| [0026](0026-startup.md) | Start-up |
| [0027](0027-performance-follow-ups.md) | Performance follow-ups: the compile, the backend's calls, and the game's own costs |
| [0028](0028-web-folder.md) | The web folder (`./incari build`) |
| [0029](0029-edit-profile.md) | Edit Profile |
| [0030](0030-game-side-optimizations.md) | Ideas from the Java optimization mods: draw lists, facing culling, sprite animation |
| [0031](0031-floor-device-profiles.md) | What the floor stand-in's profiles found: items, ambient particles, glyphs, tints, indices |
| [0032](0032-pacing-tables-light-particles.md) | Catch-up ticks paced, mesher tables kept, light maps updated in place, particles instanced |
| [0033](0033-packet-handoff.md) | Throws off the page's main thread: the packet handoff, and Via's dropped packets |
| [0034](0034-fixed-orders-lookups-and-sweeps.md) | Fixed translucent orders, the class library's lookups, collision sweeps, and Binaryen |
| [0035](0035-pool-floor.md) | A floor for the background pool's share of a late frame |
| [0036](0036-fast-and-compatible-builds.md) | Fast and Compatible builds (JSPI instead of coroutines) |
| [0037](0037-index-assets.md) | The Unicode font and the languages from the asset index |
| [0038](0038-singleplayer-in-the-page.md) | Singleplayer in the page |
| [0039](0039-singleplayer-server-in-a-worker.md) | Singleplayer's server in a Web Worker |
| [0040](0040-singleplayer-server-speed.md) | Singleplayer's server, faster: wakeups, math, spawn settings, stronghold rings |
| [0041](0041-singleplayer-storage-and-world-generation.md) | Singleplayer's files in place, a direct line to storage, and cheaper world generation |
| [0042](0042-lazy-density-branches-terrain-helpers-goal-flags.md) | Density functions' branches sampled where used, terrain helpers, and goals' flags as masks |
| [0043](0043-world-generation-garbage-jar-compaction-wasm-opt.md) | Less garbage in world generation, the client jar compacted, faster saves, and wasm-opt and channel wakeups measured again |
| [0044](0044-defaults-and-fixes-before-m4.md) | Defaults and fixes before M4 |
| [0045](0045-eagler-pause-menu-and-voice-panel.md) | An Eagler server's pause menu, voice chat in a panel, the player on the title screen |
| [0046](0046-module-framework.md) | The module framework |
| [0047](0047-screen-edits-and-skins.md) | The game's screens changed and redrawn without a patch each |
| [0048](0048-the-clients-look.md) | The client's look, Frost, in Potato and Normal presets |
| [0049](0049-hud-editor.md) | The HUD editor |
| [0050](0050-first-modules-game-view.md) | The first modules, and what they read of the game |
| [0051](0051-minimap-and-waypoints.md) | Minimap and waypoints |
| [0052](0052-hud-modules.md) | The HUD modules: Keystrokes, CPS, FPS, Reach, Coordinates, Shield & Totem |
| [0053](0053-visual-and-combat-modules.md) | The visual and combat modules: Cooldown, Crosshair, Freelook, Weather, Low Shield, 1.7 |
| [0054](0054-screen-modules.md) | The screen modules: Shulker Tooltips, Screenshot Manager, Theme Editor |
| [0055](0055-built-in-packs-and-old-textures.md) | The built-in packs in the page, and Old Textures |
| [0056](0056-the-looks-first-performance-pass.md) | The look's first performance pass |
| [0057](0057-single-file.md) | One HTML file (`./incari build --html`) |
| [0058](0058-incari-accounts-client-side.md) | Incari accounts and the online service (client side) |
| [0059](0059-incari-chat-friends-and-messages.md) | Incari chat: friends, messages and the chat menu |
| [0060](0060-update-notice.md) | The update notice |
| [0061](0061-cosmetics.md) | Cosmetics: the Wardrobe, worn cosmetics, emotes and personal effects |
| [0062](0062-incari-settings-and-the-2026-10-12-batch.md) | Incari Settings, and the 2026-10-12 batch |
| [0063](0063-the-modules-first-performance-pass.md) | The modules' first performance pass |
| [0064](0064-teavm-0-16.md) | TeaVM 0.16.0 |
