/**
 * The host page's loader: checks the browser, reads the build manifest, offers Fast (recommended) or
 * Compatible where both run (ADR 0036), streams the build with a progress bar, registers the service
 * worker that caches it, and starts the game. The game then reports its start-up steps through
 * `window.incariLoader` until its first frame, when the loading screen goes.
 *
 * Loaded by `index.html` as a module; `:app-web:pageScripts` strips the types.
 */
import { detectFeatures } from "./features.ts";
import { createJspiImports, supportsJspi } from "./jspi.ts";
import { createAsyncImports } from "./wakeups.ts";
import {
  buildFor,
  buildName,
  buildOptions,
  countingResponse,
  DEFAULT_MANIFEST,
  formatBytes,
  initialBuild,
  missingWasmGc,
  missingWebgl2,
  parseManifest,
  requestedBuild,
  sizeOf,
  versioned,
  type BuildKind,
  type BuildManifest,
  type BuildOption,
  type WasmTarget,
} from "./loader.ts";

/** What the game's code calls while it starts (`incari.app.web.LoaderPage`, platform-web's first frame). */
export interface LoaderHooks {
  /** A start-up step, shown under the bar. */
  status(text: string): void;
  /** The game has drawn its first frame: the loading screen goes. */
  done(): void;
}

/** TeaVM's WebAssembly GC loader, which `incari.wasm-runtime.js` defines. */
interface TeaVmRuntime {
  wasmGC: {
    load(
      url: string,
      options: {
        stackDeobfuscator?: { enabled: boolean; path?: string; externalInfoPath?: string };
        installImports?: (imports: Record<string, unknown>) => void;
      },
    ): Promise<{ exports: { main(args: string[]): void }; instance: WebAssembly.Instance }>;
  };
}

interface IncariWindow {
  incariLoader?: LoaderHooks;
  /** What the game reads about its build (`incari.app.web.LoaderPage`). */
  incariBuild?: {
    id: string;
    game: string;
    release: boolean;
    pack: string | null;
    programs: Record<string, { runtime: string; module: string }>;
    /** The game's own build as this page loaded it, for copies of it in workers (ADR 0039). */
    program?: { runtime: string; module: string; jspi: boolean };
  };
  TeaVM?: TeaVmRuntime;
  /** The single-file page's embedded files (offline.ts): where a script of theirs loads from. */
  incariFiles?: { base: string; url(href: string): string };
}

const page = window as unknown as IncariWindow & Window;
const options = new URLSearchParams(location.search);
/** What page-relative URLs resolve against: the single-file page's folder, whose own URL may have none. */
const pageUrl = page.incariFiles?.base ?? location.href;

const screen = element("incari-loader");
const track = element("incari-track");
const picker = element("incari-choose");
const bar = element("incari-bar");
const statusLine = element("incari-status");
const detailLine = element("incari-detail");

function element(id: string): HTMLElement {
  const found = document.getElementById(id);
  if (found === null) {
    throw new Error(`index.html has no #${id}`);
  }
  return found;
}

function show(status: string, detail = ""): void {
  statusLine.textContent = status;
  detailLine.textContent = detail;
}

/** The bar's fill, 0 to 1; null for a step that can't say how far it is (the bar pulses). */
function progress(fraction: number | null): void {
  bar.classList.toggle("indeterminate", fraction === null);
  bar.style.width = fraction === null ? "100%" : `${String(Math.round(fraction * 1000) / 10)}%`;
}

let failed = false;

/** Stops with a message the player can act on. tools/webbuild's checks watch for the console line. */
function fail(error: unknown): void {
  if (failed) {
    return;
  }
  failed = true;
  const text = error instanceof Error ? error.message : String(error);
  screen.classList.add("failed");
  screen.hidden = false;
  progress(1);
  show("The game couldn't start", text);
  // One line, so log readers that split on newlines still get the whole stack.
  const stack = error instanceof Error && error.stack !== undefined ? error.stack : text;
  console.error("[Incari] Crashed: " + stack.replace(/\n\s*/g, " | "));
}

/** Where the player's last pick is kept, to start the next choice on it. */
const BUILD_KEY = "incari.build";

function rememberedBuild(): string | null {
  try {
    return localStorage.getItem(BUILD_KEY);
  } catch {
    return null;
  }
}

function rememberBuild(kind: BuildKind): void {
  try {
    localStorage.setItem(BUILD_KEY, kind);
  } catch {
    // Storage blocked: the next choice starts on the recommended build.
  }
}

/**
 * Shows the choice between Fast and Compatible and waits for the player's pick. A build that can't
 * run here is shown disabled, with the reason. Fast is marked as the one to use wherever it works.
 */
function askForBuild(builds: readonly BuildOption[], initial: BuildKind): Promise<BuildKind> {
  show("Choose a version", "");
  track.hidden = true;
  picker.hidden = false;
  return new Promise((resolve) => {
    for (const build of builds) {
      const button = element(`incari-pick-${build.kind}`) as HTMLButtonElement;
      button.disabled = !build.available;
      element(`incari-why-${build.kind}`).textContent = build.reason ?? "";
      button.addEventListener("click", () => {
        picker.hidden = true;
        track.hidden = false;
        rememberBuild(build.kind);
        resolve(build.kind);
      });
      if (build.kind === initial) {
        button.focus();
      }
    }
  });
}

async function readManifest(): Promise<BuildManifest> {
  let response: Response;
  try {
    response = await fetch("build.json", { cache: "no-cache" });
  } catch {
    return DEFAULT_MANIFEST;
  }
  // An experiment's build (a probe) has no manifest: TeaVM's development output.
  return response.ok ? parseManifest(await response.json()) : DEFAULT_MANIFEST;
}

/**
 * Registers the service worker and waits a moment for it to take the page over, so this visit's
 * download already goes through its cache. `?sw=off` skips it (and unregisters it) for development.
 */
async function registerWorker(manifest: BuildManifest): Promise<void> {
  const workers = navigator.serviceWorker as ServiceWorkerContainer | undefined;
  // The single-file page holds every file itself, and a page opened from the disk can't have one.
  if (workers === undefined || page.incariFiles !== undefined) {
    return;
  }
  if (options.get("sw") === "off") {
    for (const registration of await workers.getRegistrations()) {
      await registration.unregister();
    }
    return;
  }
  try {
    await workers.register("sw.js", { type: "module", scope: "./" });
    await Promise.race([workers.ready, new Promise((resolve) => setTimeout(resolve, 3000))]);
    if (workers.controller !== null && manifest.files.length > 0) {
      workers.controller.postMessage({ type: "build", keep: manifest.files.map((f) => versioned(manifest, f.path)) });
    }
  } catch (error) {
    // Older browsers have no module workers: the game runs, uncached.
    console.warn("[Incari] No service worker, so nothing is cached between visits: " + String(error));
  }
}

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    // A script element fetches without `fetch`, so the single-file page's shim can't answer it.
    script.src = page.incariFiles?.url(src) ?? src;
    script.onload = () => {
      resolve();
    };
    script.onerror = () => {
      reject(new Error(`Couldn't load ${src}`));
    };
    document.head.append(script);
  });
}

/** Whether the service worker's cache already holds `url` (false where there's no cache to ask). */
async function cachedByWorker(url: string): Promise<boolean> {
  try {
    return (await caches.match(url)) !== undefined;
  } catch {
    return false;
  }
}

/**
 * Downloads `url` through `fetch` with a progress bar. TeaVM's loader fetches the module itself, so
 * the page's `fetch` is wrapped for that one URL while it does: the response it gets counts its
 * bytes and still compiles as it streams.
 *
 * Not when the service worker already has the file: nothing downloads, and the wrapper would copy
 * every chunk through JavaScript on the page's thread (a quarter of a second for the development
 * build on the desktop) and hand the browser a made-up response its compiled-code cache can't
 * recognise.
 */
async function withDownloadProgress<T>(url: string, total: number, label: string, work: () => Promise<T>): Promise<T> {
  const absolute = new URL(url, pageUrl).href;
  if (await cachedByWorker(absolute)) {
    progress(null);
    show("Starting the game", "From this browser's cache");
    return work();
  }
  const original = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const target = input instanceof Request ? input.url : new URL(String(input), pageUrl).href;
    const response = await original(input, init);
    if (target !== absolute) {
      return response;
    }
    return countingResponse(response, (received) => {
      progress(total > 0 ? Math.min(received / total, 1) : null);
      show(label, total > 0 ? `${formatBytes(received)} of ${formatBytes(total)}` : formatBytes(received));
      if (total > 0 && received >= total) {
        progress(null);
        show("Compiling the game", "");
      }
    });
  };
  try {
    return await work();
  } finally {
    window.fetch = original;
  }
}

async function startBuild(manifest: BuildManifest, kind: BuildKind, target: WasmTarget): Promise<void> {
  show("Loading", `The ${buildName(kind)} build`);
  await loadScript(versioned(manifest, target.runtime));
  const runtime = page.TeaVM;
  if (runtime === undefined) {
    throw new Error(`${target.runtime} didn't define TeaVM's loader`);
  }
  const moduleUrl = versioned(manifest, target.module);
  const debug =
    target.deobfuscator !== undefined && target.debugInfo !== undefined
      ? {
          enabled: true,
          path: versioned(manifest, target.deobfuscator),
          externalInfoPath: versioned(manifest, target.debugInfo),
        }
      : { enabled: false };
  // The Fast build suspends through JSPI (jspi.ts); Compatible imports none of it. Given wherever the
  // browser has JSPI, so an experiment's Fast build (no manifest to say so) runs too.
  const jspi = supportsJspi() ? createJspiImports(fail) : null;
  if (page.incariBuild !== undefined) {
    page.incariBuild.program = { runtime: versioned(manifest, target.runtime), module: moduleUrl, jspi: jspi !== null };
  }
  const teavm = await withDownloadProgress(moduleUrl, sizeOf(manifest, target.module), "Downloading the game", () =>
    runtime.wasmGC.load(moduleUrl, {
      stackDeobfuscator: debug,
      installImports: (imports) => {
        if (jspi !== null) {
          imports.teavmJspi = jspi.module;
        }
        // Threads woken without the browser's 4 ms timer clamp (wakeups.ts), as in singleplayer's
        // worker; on the page only when asked, until the page's frames are measured with it.
        if (options.get("wakeups") === "channel") {
          imports.teavmAsync = createAsyncImports();
        }
      },
    }),
  );
  if (jspi !== null) {
    jspi.attach(teavm.instance.exports);
    // The engine never frees a waiting fiber's stack: a page going away ends them all, or the
    // whole game stays in the tab's memory through the next load (jspi.ts). That includes a page
    // the back/forward cache keeps, whose game can't run again: it reloads if it comes back.
    window.addEventListener("pagehide", (event) => {
      console.log(
        `[Incari] Loader: page hidden${event.persisted ? " into the back/forward cache" : ""}; ending its threads`,
      );
      jspi.terminate();
    });
    window.addEventListener("pageshow", (event) => {
      if (event.persisted) {
        location.reload();
      }
    });
  }
  show("Starting", "");
  // main() returns at the game's first suspension; the rest runs from the page's event loop.
  teavm.exports.main([]);
}

async function boot(): Promise<void> {
  // The game's stacks run deep; JavaScript keeps 10 frames of a trace by default.
  Error.stackTraceLimit = 1000;
  // An error that escapes Java (a JavaScript exception in the runtime) ends the game. main()
  // returns at the first suspension, so later ones only reach the window's handlers.
  // Not one from a browser extension's script, which isn't the game's to end.
  window.addEventListener("error", (event) => {
    if (/^(chrome|moz|safari(-web)?)-extension:/.test(event.filename)) {
      console.warn(`[Incari] Ignored an extension's error: ${event.message}`);
      return;
    }
    fail(event.error ?? event.message);
  });
  window.addEventListener("unhandledrejection", (event) => {
    fail(event.reason);
  });
  page.incariLoader = {
    status: (text) => {
      if (!failed) {
        progress(null);
        show(text, "");
      }
    },
    done: () => {
      if (!failed) {
        screen.hidden = true;
      }
    },
  };

  show("Checking your browser");
  progress(null);
  const features = detectFeatures();
  const manifest = await readManifest();
  const missing = missingWebgl2(features) ?? missingWasmGc(features);
  if (missing !== null) {
    throw new Error(missing);
  }
  // The player picks where both builds can run; `?build=fast` or `?build=compatible` picks in the
  // address (the checks and the benchmark do), and a browser that can run only one runs it.
  const builds = buildOptions(features, manifest);
  const runnable = builds.filter((build) => build.available);
  if (runnable.length === 0) {
    throw new Error(builds.map((build) => build.reason).join(" "));
  }
  const requested = requestedBuild(options.get("build"));
  const only = runnable.length === 1 ? runnable[0]?.kind : undefined;
  const picked = requested ?? only ?? (await askForBuild(builds, initialBuild(builds, rememberedBuild())));
  const choice = buildFor(picked, builds, manifest);
  if (choice.kind === "unsupported") {
    throw new Error(choice.reason);
  }
  page.incariBuild = {
    id: manifest.id,
    game: manifest.game,
    release: manifest.release,
    pack: manifest.pack === undefined ? null : versioned(manifest, manifest.pack),
    // The workers' programs, by the versioned URLs their loaders fetch them from.
    programs: Object.fromEntries(
      Object.entries(manifest.programs ?? {}).map(([name, program]) => [
        name,
        { runtime: versioned(manifest, program.runtime), module: versioned(manifest, program.module) },
      ]),
    ),
  };
  const why =
    requested !== null ? "asked for in the address" : only !== undefined ? "the only one that runs here" : "picked";
  console.log(
    `[Incari] Loader: build ${manifest.id.slice(0, 12)} (${manifest.release ? "release" : "development"}), running ${buildName(
      choice.kind,
    )} (${why}); JSPI ${features.jspi ? "yes" : "no"}, cross-origin isolated ${features.crossOriginIsolated ? "yes" : "no"}`,
  );
  await registerWorker(manifest);
  const started = performance.now();
  await startBuild(manifest, choice.kind, choice.target);
  console.log(`[Incari] Loader: build loaded and started in ${String(Math.round(performance.now() - started))} ms`);
}

boot().catch(fail);
