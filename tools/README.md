# tools

Build-time and development tools. None of them ships in a build.

| Tool | What it does | Docs |
| --- | --- | --- |
| `build/` | The `./incari` CLI: fetch, decompile, apply, rebuild, build | [README](build/README.md) |
| `survey/` | The TeaVM compile survey that writes `docs/port-backlog.md` | [README](survey/README.md) |
| `copycheck/` | Fails on Incari code copied from the decompiled game | [README](copycheck/README.md) |
| `webbuild/` | The TeaVM compile, dev server, browser checks, screenshot diffs, benchmark runner | [README](webbuild/README.md) |
| `teavm-patches/` | Fixes to TeaVM 0.16.0's own sources (Apache-2.0) | [README](teavm-patches/README.md) |
| `shaders/` | Translates the game's shaders to GLSL ES at build time | [README](shaders/README.md) |
| `sprites/` | Decodes the game's sprites and their mip levels at build time | [README](sprites/README.md) |
| `wsbridge/` | A development Eagler proxy: Eagler's handshake in front of a vanilla server | [README](wsbridge/README.md) |
| `onlinedev/` | A development stand-in for the online service, in memory; `peer.mjs` is a second player for it | `build.gradle.kts`, `peer.mjs` |
| `ci/` | Repository guards CI runs: no Mojang content or builds, no publishing, DCO sign-offs, GLSL, the patch budget | each script's header |
| `git-hooks/` | The pre-commit hook `./incari setup` installs | `pre-commit` |
| `icon/` | `make_icon.py` draws Incari's icon (`incari-icon.svg`) and its PNG sizes | the script's docstring |
| `backdrop/` | `make_backdrop.py` makes the menus' blurred photo from its CC0 original | the script's docstring |
| `worldgen/` | The world generation check (ADR 0041): a server script, its properties, `compare.py` for two saved worlds | `app-desktop`'s `worldgenServer` task |
