/**
 * The single-file page (`./incari build --html`, ADR 0057): the whole build in one HTML file that
 * opens straight from the disk. `tools/webbuild`'s `SingleFile` puts every file of the web folder in
 * the page as base64 (gzipped where that helps) and this script in it, inline. It serves those files
 * to the page and to every worker by the names they already fetch them by, so the game's code is the
 * same as in the folder's build.
 *
 * A `file://` page can't fetch the files beside it, start a module worker from a Blob URL, or use the
 * origin private file system, and its workers can't read the page's Blob URLs. A classic worker from
 * a Blob URL works, shares the page's IndexedDB, and can import its own Blob URLs. So:
 *
 * - The page and every worker get a shim ({@link installFiles}) that answers `fetch`,
 *   `importScripts` and `new Worker` for the embedded files from their own Blobs.
 * - A worker starts as a classic one that installs the shim, then runs its real script (as a module
 *   if one was asked for).
 * - A worker asks the page through a port for a file it doesn't have yet.
 * - The storage worker falls back to IndexedDB by itself.
 *
 * {@link installFiles} and {@link startWorker} are copied into each worker by their source text, so
 * they use nothing from this module, only their parameters and the realm's globals.
 */

/** What a realm's shim is set up with. Everything here can be posted to a worker. */
export interface FileSetup {
  /** The page's folder: relative URLs resolve against it everywhere, since a worker's own URL is a Blob's. */
  readonly base: string;
  /** Every embedded file's path, decoded or not. */
  readonly paths: readonly string[];
  /** The files decoded so far, by path; the scripts always are, since `importScripts` can't wait. */
  readonly blobs: Readonly<Record<string, Blob>>;
  /** The source of a worker's first script: {@link startWorker} given {@link installFiles}. */
  readonly shim: string;
  /** In a worker: where to ask for a file it doesn't have. */
  readonly port?: MessagePort;
  /** In a worker: the script it was started with, and whether to run it as a module. */
  readonly script?: WorkerScript;
}

/** A worker's real script: an embedded file (`path`), or a Blob the page made from source. */
export interface WorkerScript {
  readonly path: string | null;
  readonly blob: Blob;
  readonly module: boolean;
}

/** What a realm's shim offers its code. */
export interface FileAccess {
  /** The page's folder ({@link FileSetup.base}): resolve page-relative names against it, not `location`. */
  readonly base: string;
  /** A URL this realm can load an embedded file's script from (a Blob URL), or `href` itself. */
  url(href: string): string;
  /** A Blob URL of an embedded module whose relative imports point at Blob URLs of their own. */
  moduleUrl(path: string): Promise<string>;
  /** Runs a worker's real script. */
  start(script: WorkerScript): Promise<void>;
}

/** The globals the shim replaces or uses. */
export interface FileScope {
  fetch: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
  Worker?: new (url: string | URL, options?: WorkerOptions) => Worker;
  importScripts?: (...urls: string[]) => void;
  incariFiles?: FileAccess;
}

/** A file the page decodes when it's first asked for. */
export type Decoder = (path: string) => Promise<Blob | null>;

/**
 * Installs the shim on `scope`. `decode` is the page's; a worker asks `setup.port` instead.
 * Self-contained: copied into workers by its source.
 */
export function installFiles(scope: FileScope, setup: FileSetup, decode?: Decoder): FileAccess {
  const all = new Set(setup.paths);
  const blobs = new Map<string, Blob>(Object.entries(setup.blobs));
  const pending = new Map<string, Promise<Blob | null>>();
  // Blob URLs made in this realm: by embedded file, and the modules' back to their file, so a worker
  // started from `import.meta.url` (game-worker.ts) is one of the files again.
  const urls = new Map<string, string>();
  const modules = new Map<string, Promise<string>>();
  const moduleFiles = new Map<string, string>();
  // Script Blobs this realm made URLs of (the storage and program workers' sources), to start
  // those workers from.
  const scripts = new Map<string, Blob>();
  const realFetch = scope.fetch.bind(scope);
  const RealWorker = scope.Worker;
  const realImportScripts = scope.importScripts?.bind(scope);
  const createObjectUrl = URL.createObjectURL.bind(URL);
  const revokeObjectUrl = URL.revokeObjectURL.bind(URL);

  const asked = new Map<number, (blob: Blob | null) => void>();
  let nextAsk = 0;
  const port = setup.port;
  if (port !== undefined) {
    port.onmessage = (event: MessageEvent<{ id: number; blob: Blob | null }>) => {
      asked.get(event.data.id)?.(event.data.blob);
      asked.delete(event.data.id);
    };
  }
  const fromPage: Decoder =
    decode ??
    ((path) =>
      port === undefined
        ? Promise.resolve(null)
        : new Promise((resolve) => {
            const id = nextAsk++;
            asked.set(id, resolve);
            port.postMessage({ id, path });
          }));

  /** The embedded file `url` names, relative to the page's folder, or null if it names none. */
  function fileOf(url: string): string | null {
    const module = moduleFiles.get(url);
    if (module !== undefined) {
      return module;
    }
    let href: string;
    try {
      href = new URL(url, setup.base).href;
    } catch {
      return null;
    }
    if (!href.startsWith(setup.base)) {
      return null;
    }
    // Versioned URLs (`?v=<hash>`, loader.ts) name the same file.
    const path = decodeURIComponent(href.slice(setup.base.length).replace(/[?#].*$/s, ""));
    return all.has(path) ? path : null;
  }

  function blobOf(path: string): Promise<Blob | null> {
    const known = blobs.get(path);
    if (known !== undefined) {
      return Promise.resolve(known);
    }
    let waiting = pending.get(path);
    if (waiting === undefined) {
      waiting = fromPage(path).then((blob) => {
        if (blob !== null) {
          blobs.set(path, blob);
        }
        return blob;
      });
      pending.set(path, waiting);
    }
    return waiting;
  }

  function urlOf(path: string): string {
    let url = urls.get(path);
    if (url === undefined) {
      const blob = blobs.get(path);
      if (blob === undefined) {
        throw new Error(`${path} isn't loaded yet`);
      }
      url = createObjectUrl(blob);
      urls.set(path, url);
    }
    return url;
  }

  /** The page's scripts import no cycle, so each module's imports are made first. */
  function moduleUrl(path: string): Promise<string> {
    let made = modules.get(path);
    if (made === undefined) {
      made = (async () => {
        const blob = await blobOf(path);
        if (blob === null) {
          throw new Error(`No ${path} in this file`);
        }
        const source = await blob.text();
        const imports = /(\b(?:from|import)\s*\(?\s*)(["'])(\.{1,2}\/[^"'\n]+)\2/g;
        const targets = new Map<string, string>();
        for (const match of source.matchAll(imports)) {
          const specifier = match[3] ?? "";
          const target = fileOf(new URL(specifier, setup.base + path).href);
          if (target !== null && !targets.has(specifier)) {
            targets.set(specifier, await moduleUrl(target));
          }
        }
        const rewritten = source.replace(imports, (whole: string, before: string, quote: string, specifier: string) => {
          const target = targets.get(specifier);
          return target === undefined ? whole : before + quote + target + quote;
        });
        const url = createObjectUrl(new Blob([rewritten], { type: "text/javascript" }));
        moduleFiles.set(url, path);
        return url;
      })();
      modules.set(path, made);
    }
    return made;
  }

  scope.fetch = async (input, init) => {
    const path = fileOf(input instanceof Request ? input.url : String(input));
    if (path === null) {
      return realFetch(input, init);
    }
    const blob = await blobOf(path);
    return blob === null ? new Response(null, { status: 404, statusText: "Not Found" }) : new Response(blob);
  };

  URL.createObjectURL = (object) => {
    const url = createObjectUrl(object);
    if (object instanceof Blob && object.type.includes("javascript")) {
      scripts.set(url, object);
    }
    return url;
  };
  URL.revokeObjectURL = (url) => {
    scripts.delete(url);
    revokeObjectUrl(url);
  };

  if (realImportScripts !== undefined) {
    scope.importScripts = (...requested) => {
      realImportScripts(
        ...requested.map((url) => {
          const path = fileOf(url);
          return path === null ? url : urlOf(path);
        }),
      );
    };
  }

  let shimUrl: string | null = null;
  if (RealWorker !== undefined) {
    // A function, not a class: `new` returns the real worker it makes.
    const OfflineWorker = function (url: string | URL, options?: WorkerOptions): Worker {
      const href = String(url);
      const path = fileOf(href);
      const blob = path === null ? scripts.get(href) : blobs.get(path);
      if (blob === undefined) {
        return new RealWorker(url, options);
      }
      shimUrl ??= createObjectUrl(new Blob([setup.shim], { type: "text/javascript" }));
      const worker = new RealWorker(shimUrl, options?.name === undefined ? {} : { name: options.name });
      const channel = new MessageChannel();
      channel.port1.onmessage = (event: MessageEvent<{ id: number; path: string }>) => {
        void blobOf(event.data.path).then((found) => {
          channel.port1.postMessage({ id: event.data.id, blob: found });
        });
      };
      const files: FileSetup = {
        base: setup.base,
        paths: setup.paths,
        blobs: Object.fromEntries(blobs),
        shim: setup.shim,
        port: channel.port2,
        script: { path, blob, module: options?.type === "module" },
      };
      worker.postMessage({ incariFiles: files }, [channel.port2]);
      return worker;
    };
    scope.Worker = OfflineWorker as unknown as new (url: string | URL, options?: WorkerOptions) => Worker;
  }

  const access: FileAccess = {
    base: setup.base,
    url(href) {
      const path = fileOf(href);
      return path === null ? href : urlOf(path);
    },
    moduleUrl,
    async start(script) {
      if (script.module) {
        await import(script.path === null ? createObjectUrl(script.blob) : await moduleUrl(script.path));
      } else if (realImportScripts !== undefined) {
        realImportScripts(createObjectUrl(script.blob));
      }
    },
  };
  scope.incariFiles = access;
  return access;
}

/** The parts of a worker's scope {@link startWorker} uses. */
export interface StartScope extends FileScope {
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  removeEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  dispatchEvent(event: Event): boolean;
  setTimeout(handler: () => void, timeout: number): unknown;
}

/**
 * A worker's first script: waits for the page's files, installs the shim, and runs the real script.
 * Messages that come before that script listens are held and handed to it in order. Self-contained:
 * copied into workers by its source.
 */
export function startWorker(scope: StartScope, install: typeof installFiles): void {
  const held: MessageEvent<unknown>[] = [];
  const hold = (event: MessageEvent<unknown>): void => {
    held.push(event);
  };
  const first = (event: MessageEvent<{ incariFiles?: FileSetup } | null>): void => {
    const setup = event.data?.incariFiles;
    if (setup?.script === undefined) {
      return;
    }
    scope.removeEventListener("message", first);
    scope.addEventListener("message", hold);
    install(scope, setup)
      .start(setup.script)
      .then(
        () => {
          scope.removeEventListener("message", hold);
          for (const message of held.splice(0)) {
            scope.dispatchEvent(new MessageEvent("message", { data: message.data, ports: [...message.ports] }));
          }
        },
        (error: unknown) => {
          // An uncaught error, so the worker's `onerror` on the other side hears of it.
          scope.setTimeout(() => {
            throw error;
          }, 0);
        },
      );
  };
  scope.addEventListener("message", first);
}

/** The source of a worker's first script. */
export function shimSource(): string {
  return `"use strict";\n(${startWorker.toString()})(self, ${installFiles.toString()});\n`;
}

/** How `SingleFile` marks an embedded file: `<script type=...>` with the base64 as its text. */
export const FILE_TYPE = "application/x-incari-file";

/** One embedded file's element, as the page reads it. */
export interface EmbeddedFile {
  readonly path: string;
  /** The file's media type, for the Blob (`application/wasm` lets the browser compile while reading). */
  readonly type: string;
  readonly gzip: boolean;
  /** The base64; read once, then the element goes. */
  text(): string;
}

/** Decodes one embedded file into a Blob of its type. */
export async function decodeFile(file: EmbeddedFile, realFetch: FileScope["fetch"]): Promise<Blob> {
  const text = file.text();
  const fromBase64 = (Uint8Array as { fromBase64?: (text: string) => Uint8Array<ArrayBuffer> }).fromBase64;
  // Browsers before Uint8Array.fromBase64 decode a data: URL natively, which is far quicker than atob.
  const bytes =
    fromBase64 === undefined
      ? new Blob([await (await realFetch(`data:application/octet-stream;base64,${text}`)).arrayBuffer()])
      : new Blob([fromBase64(text)]);
  const body = file.gzip
    ? await new Response(bytes.stream().pipeThrough(new DecompressionStream("gzip"))).blob()
    : bytes;
  return new Blob([body], { type: file.type });
}

/**
 * The folder for the embedded files' relative names when the page's own URL has none: an
 * `about:blank` tab the page was written into, or a `blob:` or `data:` URL (how players open the
 * file where `file://` is blocked). Only the shim resolves against it, so it needn't exist.
 */
export const FALLBACK_BASE = "file:///incari/";

/** The folder of the page at `href`, or {@link FALLBACK_BASE} where `href` has none. */
export function pageBase(href: string): string {
  try {
    return new URL(".", href).href;
  } catch {
    return FALLBACK_BASE;
  }
}

/**
 * The page's side: installs the shim over the embedded files and starts `boot.js`. The scripts are
 * decoded first (workers and `importScripts` need them at once); the rest when first fetched, so
 * the build the player doesn't pick and the languages they don't use are never decoded.
 */
export async function startPage(page: Window & FileScope, files: readonly EmbeddedFile[]): Promise<void> {
  const realFetch = page.fetch.bind(page);
  const byPath = new Map(files.map((file) => [file.path, file]));
  const blobs: Record<string, Blob> = {};
  for (const file of files) {
    if (file.path.endsWith(".js")) {
      blobs[file.path] = await decodeFile(file, realFetch);
      byPath.delete(file.path);
    }
  }
  const decodeLater: Decoder = async (path) => {
    const file = byPath.get(path);
    if (file === undefined) {
      return null;
    }
    byPath.delete(path);
    return decodeFile(file, realFetch);
  };
  const access = installFiles(
    page,
    {
      base: pageBase(page.location.href),
      paths: files.map((file) => file.path),
      blobs,
      shim: shimSource(),
    },
    decodeLater,
  );
  const boot = page.document.createElement("script");
  boot.type = "module";
  boot.src = await access.moduleUrl("boot.js");
  page.document.body.append(boot);
}

/** The embedded files in `document`, each element removed once its text is read. */
export function embeddedFiles(document: Document): EmbeddedFile[] {
  return Array.from(document.querySelectorAll<HTMLScriptElement>(`script[type="${FILE_TYPE}"]`), (element) => ({
    path: element.dataset.path ?? "",
    type: element.dataset.type ?? "application/octet-stream",
    gzip: element.dataset.encoding === "gzip",
    text: () => {
      const text = element.text;
      element.remove();
      return text;
    },
  }));
}

/**
 * Makes `navigator.sendBeacon` say it couldn't queue the beacon (false) instead of throwing, as
 * Chromium does on a `file://` page. Scripts that aren't the game's (school monitoring, analytics)
 * beacon when the tab is hidden, and their uncaught error would end the game (boot.ts).
 */
export function quietBeacons(navigator: Pick<Navigator, "sendBeacon">): void {
  const send = navigator.sendBeacon.bind(navigator);
  navigator.sendBeacon = (url, data) => {
    try {
      return send(url, data);
    } catch {
      return false;
    }
  };
}

/** What `SingleFile` calls after this script: starts the page, or says on the loading screen why it couldn't. */
export function start(page: Window & FileScope): void {
  if (typeof page.navigator.sendBeacon === "function") {
    quietBeacons(page.navigator);
  }
  startPage(page, embeddedFiles(page.document)).catch((error: unknown) => {
    const status = page.document.getElementById("incari-status");
    const detail = page.document.getElementById("incari-detail");
    if (status !== null && detail !== null) {
      status.textContent = "The game couldn't start";
      detail.textContent = String(error);
    }
    console.error(
      "[Incari] Crashed: " + (error instanceof Error && error.stack !== undefined ? error.stack : String(error)),
    );
  });
}
