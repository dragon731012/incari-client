package incari.platform.web.storage;

import org.jspecify.annotations.Nullable;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The page's side of the storage worker ({@code src/main/js/storage-worker.js}), with every
 * JavaScript snippet the file system runs. It starts the worker, sends it batches of changes, and
 * waits for the two answers the game needs at start-up (the stored files and the client jar).
 *
 * <p>Waiting suspends the calling coroutine through {@link Async}; the worker's replies complete it
 * from a message listener. Nothing else waits: a batch is posted and forgotten, and its outcome is
 * read at the next flush ({@link #takeProblem}).
 */
final class BrowserStore {

    private BrowserStore() {}

    /**
     * Starts the worker from its source, and asks the browser not to evict the page's storage under
     * pressure (it may refuse). For the browser checks and the console, also offers
     * {@code window.incariStorage}: {@code read(path)} (a promise of the file's text),
     * {@code list(path)} (the paths under a folder) and {@code pending()} (batches not yet stored).
     */
    @JSBody(
            params = "source",
            script = "var url = URL.createObjectURL(new Blob([source], {type: 'text/javascript'}));\n"
                    + "var worker = new Worker(url, {name: 'incari-storage'});\n"
                    + "var store = {worker: worker, calls: {}, next: 1, pending: 0, problems: []};\n"
                    + "worker.onmessage = function(e) { var m = e.data;\n"
                    + "  if (m.type === 'applied') { store.pending--;"
                    + "    if (m.error) store.problems.push('saving failed: ' + m.error); return; }\n"
                    + "  var call = store.calls[m.id]; if (call) { delete store.calls[m.id]; call(m); } };\n"
                    + "worker.onerror = function(e) { store.problems.push('storage worker: ' + (e.message || e)); };\n"
                    + "store.call = function(message, callback) { message.id = store.next++;"
                    + "  store.calls[message.id] = callback; worker.postMessage(message); };\n"
                    + "window.incariStorage = {\n"
                    + "  read: function(path) { return new Promise(function(resolve) {"
                    + "    store.call({type: 'read', path: path}, function(m) {"
                    + "      resolve(m.data ? new TextDecoder().decode(m.data) : null); }); }); },\n"
                    + "  list: function(path) { return new Promise(function(resolve) {"
                    + "    store.call({type: 'list', path: path}, function(m) { resolve(m.paths); }); }); },\n"
                    + "  pending: function() { return store.pending; },\n"
                    // ADR 0041: a port of the storage worker's own for another worker (singleplayer's
                    // server), whose changes then go straight to it, in the same queue as the page's.
                    + "  connect: function() { var channel = new MessageChannel();"
                    + "    worker.postMessage({type: 'port', port: channel.port1}, [channel.port1]);"
                    + "    return channel.port2; } };\n"
                    + "if (navigator.storage && navigator.storage.persist) {"
                    + "  navigator.storage.persist().then(function() {}, function() {}); }\n"
                    + "return store;")
    static native JSObject start(String source);

    /**
     * In a worker the page gave a port of the storage worker's ({@code connect}, ADR 0041): a store
     * object over that port, which the calls here use as they use the page's. Null without one.
     */
    @JSBody(
            script = "var port = self.__incariStorage; if (!port) return null;"
                    + "var store = {worker: port, calls: {}, next: 1, pending: 0, problems: []};"
                    + "port.onmessage = function(e) { var m = e.data;"
                    + "  if (m.type === 'applied') { store.pending--;"
                    + "    if (m.error) store.problems.push('saving failed: ' + m.error); return; }"
                    + "  var call = store.calls[m.id]; if (call) { delete store.calls[m.id]; call(m); } };"
                    + "store.call = function(message, callback) { message.id = store.next++;"
                    + "  store.calls[message.id] = callback; port.postMessage(message); };"
                    + "return store;")
    static native @Nullable JSObject fromPort();

    // --- Opening: the stored files ----------------------------------------------------------

    /**
     * Loads everything under {@code root}, but for in-place files (ADR 0041), listed with their
     * sizes; suspends until the worker has read it all.
     */
    @Async
    static native JSObject open(JSObject store, String root, String lock);

    private static void open(JSObject store, String root, String lock, AsyncCallback<JSObject> callback) {
        JSObject message = request("open", "root", root, "lock", lock);
        setInPlace(message, InPlaceFiles.WITHIN);
        call(store, message, callback::complete);
    }

    @JSBody(
            params = {"message", "within"},
            script = "message.inPlace = {within: within, suffixes: ['.mca', '.mcc']};")
    private static native void setInPlace(JSObject message, String within);

    @JSBody(
            params = {"reply", "index"},
            script = "return !!reply.entries[index].stored;")
    static native boolean entryIsStored(JSObject reply, int index);

    @JSBody(
            params = {"reply", "index"},
            script = "return reply.entries[index].size | 0;")
    static native int entrySize(JSObject reply, int index);

    // --- In-place files and waiting for the store (ADR 0041) ----------------------------------

    /** A stored file's bytes, or null if it isn't stored; suspends until read. */
    @Async
    static native @Nullable ArrayBuffer read(JSObject store, String path);

    private static void read(JSObject store, String path, AsyncCallback<@Nullable ArrayBuffer> callback) {
        call(store, request("read", "path", path, "path", path), reply -> callback.complete(dataOrNull(reply)));
    }

    @JSBody(params = "reply", script = "return reply.data || null;")
    private static native @Nullable ArrayBuffer dataOrNull(JSObject reply);

    /** Everything stored under {@code path}, with sizes and without bytes ({@code entries}, as {@link #open}'s). */
    @Async
    static native JSObject stat(JSObject store, String path);

    private static void stat(JSObject store, String path, AsyncCallback<JSObject> callback) {
        call(store, request("stat", "path", path, "path", path), callback::complete);
    }

    /** Suspends until everything sent before it is stored. */
    @Async
    static native JSObject sync(JSObject store);

    private static void sync(JSObject store, AsyncCallback<JSObject> callback) {
        call(store, request("sync", "id", "0", "id", "0"), callback::complete);
    }

    @JSBody(params = "reply", script = "return String(reply.backend);")
    static native String backend(JSObject reply);

    @JSBody(params = "reply", script = "return !!reply.readOnly;")
    static native boolean readOnly(JSObject reply);

    @JSBody(params = "reply", script = "return !!reply.locked;")
    static native boolean locked(JSObject reply);

    @JSBody(params = "reply", script = "return String(reply.problems || '');")
    static native String problems(JSObject reply);

    @JSBody(params = "reply", script = "return reply.entries.length;")
    static native int entryCount(JSObject reply);

    @JSBody(
            params = {"reply", "index"},
            script = "return String(reply.entries[index].path);")
    static native String entryPath(JSObject reply, int index);

    @JSBody(
            params = {"reply", "index"},
            script = "return !!reply.entries[index].dir;")
    static native boolean entryIsDirectory(JSObject reply, int index);

    @JSBody(
            params = {"reply", "index"},
            script = "return +reply.entries[index].mtime;")
    static native double entryLastModified(JSObject reply, int index);

    /** A file entry's bytes, handed over once: the page's copy is dropped as it's read. */
    @JSBody(
            params = {"reply", "index"},
            script = "var e = reply.entries[index]; var d = e.data; e.data = null;"
                    + "return new Int8Array(d || new ArrayBuffer(0));")
    static native Int8Array takeEntryData(JSObject reply, int index);

    // --- Changes ----------------------------------------------------------------------------

    @JSBody(script = "return [];")
    static native JSObject newBatch();

    @JSBody(
            params = {"batch", "path", "mtime"},
            script = "batch.push({op: 'mkdir', path: path, mtime: mtime});")
    static native void addMkdir(JSObject batch, String path, double mtime);

    @JSBody(
            params = {"batch", "path", "size", "at", "data", "mtime"},
            script = "batch.push({op: 'write', path: path, size: size, at: at,"
                    // A copy of its own: the batch's buffers are moved to the worker, and under
                    // TeaVM's JavaScript backend data may be the Java array itself.
                    + " data: data ? new Uint8Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.length))"
                    + " : null, mtime: mtime});")
    static native void addWrite(JSObject batch, String path, int size, int at, @Nullable Int8Array data, double mtime);

    @JSBody(
            params = {"batch", "path"},
            script = "batch.push({op: 'delete', path: path});")
    static native void addDelete(JSObject batch, String path);

    /** Hands a batch to the worker, its bytes moved rather than copied, without waiting. */
    @JSBody(
            params = {"store", "batch"},
            script = "var buffers = [];"
                    + "for (var i = 0; i < batch.length; i++) { var d = batch[i].data;"
                    + "  if (d && buffers.indexOf(d.buffer) < 0) buffers.push(d.buffer); }"
                    + "store.pending++; store.worker.postMessage({type: 'apply', ops: batch}, buffers);")
    static native void apply(JSObject store, JSObject batch);

    /** Batches handed over but not yet stored. */
    @JSBody(params = "store", script = "return store.pending;")
    static native int pending(JSObject store);

    /** The oldest problem the worker reported and nobody has read yet, or null. */
    @JSBody(params = "store", script = "return store.problems.length ? String(store.problems.shift()) : null;")
    static native @Nullable String takeProblem(JSObject store);

    // --- The client jar ---------------------------------------------------------------------

    /**
     * The file at {@code url}, from the worker's cache under {@code key} or downloaded (and then
     * cached). Suspends until it's there; its bytes stay in the page, outside the game's heap.
     */
    @Async
    static native JSObject download(JSObject store, String url, String key);

    private static void download(JSObject store, String url, String key, AsyncCallback<JSObject> callback) {
        call(store, request("jar", "url", absolute(url), "key", key), callback::complete);
    }

    /** Drops the cached copy under {@code key} (it was damaged); suspends until it's gone. */
    @Async
    static native JSObject forget(JSObject store, String key);

    private static void forget(JSObject store, String key, AsyncCallback<JSObject> callback) {
        call(store, request("forget", "key", key, "key", key), callback::complete);
    }

    @JSBody(params = "reply", script = "return reply.error ? String(reply.error) : null;")
    static native @Nullable String error(JSObject reply);

    @JSBody(params = "reply", script = "return !!reply.cached;")
    static native boolean cached(JSObject reply);

    @JSBody(params = "reply", script = "return reply.data;")
    static native ArrayBuffer data(JSObject reply);

    /** {@code length} bytes of {@code buffer} from {@code position}, copied into the game's heap. */
    @JSBody(
            params = {"buffer", "position", "length"},
            script = "return new Int8Array(buffer.slice(position, position + length));")
    static native Int8Array slice(ArrayBuffer buffer, int position, int length);

    /** A list for {@link #copyEntries}: local header, compressed size, local header, ... */
    @JSBody(script = "return [];")
    static native JSObject newRanges();

    @JSBody(
            params = {"ranges", "start", "length"},
            script = "ranges.push(start, length);")
    static native void addRange(JSObject ranges, int start, int length);

    /**
     * The zip entries listed in {@code entries} (each a local header's offset and its data's
     * compressed size), copied from {@code buffer} one after another into a new buffer: each its
     * local header (30 bytes, then its name and extra field, whose lengths it gives) and its data.
     * The reply has the buffer as {@code data}, where each entry now starts as {@code starts}, or
     * an {@code error}.
     */
    @JSBody(
            params = {"buffer", "entries"},
            script = "var from = new Uint8Array(buffer), n = entries.length / 2, spans = new Int32Array(n), total = 0;"
                    + " for (var i = 0; i < n; i++) { var h = entries[2 * i];"
                    + "   if (h + 30 > from.length || from[h] !== 0x50 || from[h + 1] !== 0x4b || from[h + 2] !== 3 || from[h + 3] !== 4)"
                    + "     return {error: 'Corrupt zip file (bad local header at ' + h + ')'};"
                    + "   spans[i] = 30 + (from[h + 26] | from[h + 27] << 8) + (from[h + 28] | from[h + 29] << 8) + entries[2 * i + 1];"
                    + "   if (h + spans[i] > from.length) return {error: 'Corrupt zip file (entry past the end at ' + h + ')'};"
                    + "   total += spans[i]; }"
                    + " var to = new Uint8Array(total), starts = new Int32Array(n), at = 0;"
                    + " for (var j = 0; j < n; j++) { var s = entries[2 * j];"
                    + "   to.set(from.subarray(s, s + spans[j]), at); starts[j] = at; at += spans[j]; }"
                    + " return {data: to.buffer, starts: starts};")
    static native JSObject copyEntries(ArrayBuffer buffer, JSObject entries);

    @JSBody(
            params = {"copy", "index"},
            script = "return copy.starts[index];")
    static native int copiedStart(JSObject copy, int index);

    // --- Plumbing ---------------------------------------------------------------------------

    @JSFunctor
    interface Reply extends JSObject {
        void reply(JSObject message);
    }

    @JSBody(
            params = {"store", "message", "reply"},
            script = "store.call(message, function(m) { reply(m); });")
    private static native void call(JSObject store, JSObject message, Reply reply);

    @JSBody(
            params = {"type", "key1", "value1", "key2", "value2"},
            script = "var m = {type: type}; m[key1] = value1; m[key2] = value2; return m;")
    private static native JSObject request(String type, String key1, String value1, String key2, String value2);

    /**
     * The worker runs from a Blob URL, so it can't resolve a URL relative to the page itself. Nor can
     * the single-file page by its own URL, which may have no folder (offline.ts).
     */
    @JSBody(
            params = "url",
            script = "return new URL(url, window.incariFiles ? window.incariFiles.base : location.href).href;")
    private static native String absolute(String url);
}
