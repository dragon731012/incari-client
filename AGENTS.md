# Agent instructions for Incari

Rules and commands for AI agents (and people) working here. Each module also has an `AGENTS.md`
with its own rules and a `README.md` saying what it does and how to test it.

## The project

Incari ports Minecraft: Java Edition's latest release to the browser and builds a PvP/QoL client on
it. Top priority: performance on typical crappy school Chromebooks.

- **Patch-only:** the game is downloaded and decompiled into `work/` (git-ignored); the repo holds
  Incari's code and `patches/` (ADR 0001).
- **Target:** Minecraft 26.3 (unobfuscated, Java 25).
- **Compiler:** TeaVM 0.16.0, WebAssembly GC, two builds: Fast (JSPI) and Compatible (coroutines)
  (ADR 0036). Vineflower 1.12.0 decompiles. Both pinned exactly.
- **Graphics:** WebGL2 under Mojang's `GpuDevice` API, shaders translated at build time.
- **Networking:** WebSocket, Eagler's handshake, and ViaVersion in the client (any server 1.8+).

| To learn | Read |
| --- | --- |
| Layers and modules | [`ARCHITECTURE.md`](ARCHITECTURE.md), `gradle/module-boundaries.txt` |
| Why it's built this way | [`docs/adr/README.md`](docs/adr/README.md) |
| Threads in the browser | [`docs/threading.md`](docs/threading.md) |
| Measuring performance | [`docs/benchmark.md`](docs/benchmark.md) |

## Hard rules

- **Never commit Mojang files or anything from `work/`**: jars, decompiled source, textures,
  sounds, models, language files, shaders (translated too), data dumps.
- **Never commit or publish a build**, or link to a hosted one.
- **Never copy decompiled code into an Incari module.** `./incari copycheck` checks.
- **Never hand-edit `patches/`.** Commit in `work/client`, then `./incari rebuild`.
- **Never reformat vendor code** or edit it to silence a warning.
- **Never commit secrets.** Use environment variables.
- **Run the module's tests before finishing**, and say what you ran.

## Commands

Needs JDK 25+, git and Node 24+.

```sh
./incari setup                 # fresh clone -> patched tree in work/client
./gradlew check                # all Java gates and tests, plus the client build
./gradlew :<module>:check      # one module
./gradlew spotlessApply        # format Java
npm ci && npm run check        # TypeScript
./incari rebuild [--check]     # work/client commits -> patches/ (--check: fail if stale)
./incari copycheck             # nothing copied from the game
tools/ci/check-patch-budget.sh # patch size against patches/budget.properties

./incari assets                # once: the game's sounds and languages
./gradlew :app-desktop:run     # play on the desktop
./gradlew :app-desktop:smokeTest   # unattended session (also smokeHost + smokeJoin)
./gradlew :app-web:teavm       # browser build, ~10 minutes, ~20 GB memory
./gradlew :app-web:serve       # serve it on localhost
./gradlew :app-web:browserCheck    # run it headless until the game loop ticks
```

- Smoke tasks can say `BUILD SUCCESSFUL` with failures inside. Read the report they write.
- `./incari survey` must run on exactly JDK 25.

## Where you can run what

- **Rendering, audio and performance need a real GPU and display.** Without one, build and run
  unit tests, but don't claim rendering or performance results. `browserCheck` uses SwiftShader by
  default; `-Pbrowser.gpu=gl` uses the GPU.
- **Network:** allow Mojang (`piston-meta.mojang.com`, `piston-data.mojang.com`,
  `resources.download.minecraft.net`), Maven Central and `repo.viaversion.com`.

## Changing game code

1. Edit `work/client/src/main/java`: a one- or two-line hook into an Incari module, marked
   `// Incari start: <reason>` and `// Incari end`.
2. Commit in `work/client`, one concern each, subject `<subsystem>: <summary>`.
3. `./incari rebuild`, then commit `patches/` here.

Whole-class deletes or replacements go in `patches/classes.txt`.

## Where code goes

- Logic goes in a strict module (`platform-*`, `render-*`, `net`, `mesher`, `framework`, `online`,
  `features/*`). `client`'s own code is only the adapter the patches call.
- Platform modules never see game types. `features/*` depend only on `framework`.
- Use Mojang's official names. Comments explain why, especially browser limits ("must not block",
  "worker-side, no game objects", "hot path, don't allocate").
- Player-facing text is plain words, fitted to its box (`Ui.fit`, `Ui.wrap`). Screens fit 320x240
  GUI units; `./gradlew :app-desktop:smokeLayout` checks.

## Docs

Write short: the point first, plain words, tables and lists over paragraphs, each fact once.

- New decision → an ADR in `docs/adr/` plus a line in its index.
- Module changed → its `README.md`, and `ARCHITECTURE.md` if the module table changes.

## Commits

Sign off every commit (`git commit -s`). Subjects are `<area>: <summary>`. Renderer, networking,
patch and licensing changes need a human reviewer (`.github/CODEOWNERS`).
