package incari.platform.web;

import incari.platform.api.threads.WorkerException;
import incari.platform.api.threads.WorkerJob;
import incari.platform.api.threads.WorkerPool;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import org.jspecify.annotations.Nullable;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Web Workers running a program of the build's beside the game (ADR 0023): each loads the
 * program's own WebAssembly module ({@code <program>.wasm}, TeaVM's loader beside it) through a
 * small bootstrap script ({@code src/main/js/program-worker.js}) and answers the messages the game
 * sends it.
 *
 * <p>Messages are copied out of the game's linear memory as they're sent: into a slot of a
 * {@code SharedArrayBuffer} where the page is cross-origin isolated, else into an
 * {@code ArrayBuffer} handed over to the worker. Answers come back in an {@code ArrayBuffer} of
 * their own and are copied into a direct buffer (linear memory) when their job is awaited. A job
 * awaited from the background pool steps aside for the wait ({@link IdleTimeExecutor#awaitOutside}),
 * so the pool keeps other jobs going.
 *
 * <p>Everything here runs on the page's one thread.
 */
final class WebWorkerPool implements WorkerPool {

    /** Bytes per slot of the shared message buffer; a longer message is handed over instead. */
    private static final int SLOT_BYTES = 64 * 1024;

    private static final int SLOTS = 32;

    private final JSObject pool;
    private final int size;
    private final ArrayDeque<ByteBuffer> replyBuffers = new ArrayDeque<>();
    /**
     * Messages are copied here and replies land here, each read through a view kept from one job to
     * the next (ADR 0027): a JavaScript object handed to Java is wrapped and kept in the runtime's
     * weak map, and two views a job, hundreds a second while terrain loads, made major collections
     * pause walking that map. Made again only when replaced or detached by growing memory.
     */
    private ByteBuffer staging = ByteBuffer.allocateDirect(64 * 1024);

    private @Nullable Int8Array stagingView;
    private ByteBuffer replyStaging = ByteBuffer.allocateDirect(256 * 1024);
    private @Nullable Int8Array replyView;
    private int inFlight;

    private WebWorkerPool(JSObject pool, int size) {
        this.pool = pool;
        this.size = size;
    }

    /**
     * Starts the workers for {@code program}, or returns null if the page can't: no Workers, or no
     * program by that name in the build.
     */
    static @Nullable WebWorkerPool start(String program) {
        if (!hasWorkers()) {
            return null;
        }
        String runtime = programUrl(program, "runtime", program + ".wasm-runtime.js");
        String module = programUrl(program, "module", program + ".wasm");
        int count = Math.max(1, Math.min(4, hardwareConcurrency() - 1));
        JSObject pool = createPool(ProgramWorkerSource.SCRIPT, program, count, runtime, module, SLOT_BYTES, SLOTS);
        return new WebWorkerPool(pool, count);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void broadcast(ByteBuffer message) {
        int length = stage(message);
        if (!broadcast(pool, stagingView(false), length)) {
            broadcast(pool, stagingView(true), length);
        }
    }

    @Override
    public WorkerJob submit(ByteBuffer message) {
        int length = stage(message);
        int id = submit(pool, stagingView(false), length);
        if (id < 0) {
            id = submit(pool, stagingView(true), length);
        }
        inFlight++;
        return new Job(id);
    }

    @Override
    public int inFlight() {
        return inFlight;
    }

    /** Copies the message's remaining bytes to the start of {@link #staging}; their count. */
    private int stage(ByteBuffer message) {
        int length = message.remaining();
        if (staging.capacity() < length) {
            staging = ByteBuffer.allocateDirect(Math.max(length, staging.capacity() * 2));
            stagingView = null;
        }
        staging.put(0, message, message.position(), length);
        return length;
    }

    private Int8Array stagingView(boolean remake) {
        Int8Array view = stagingView;
        if (view == null || remake) {
            view = Int8Array.fromJavaBuffer(staging);
            stagingView = view;
        }
        return view;
    }

    /** The reply buffer's kept view, the buffer grown to {@code length} first. */
    private Int8Array replyView(int length, boolean remake) {
        if (replyStaging.capacity() < length) {
            replyStaging = ByteBuffer.allocateDirect(Math.max(length, replyStaging.capacity() * 2));
            replyView = null;
        }
        Int8Array view = replyView;
        if (view == null || remake) {
            view = Int8Array.fromJavaBuffer(replyStaging);
            replyView = view;
        }
        return view;
    }

    private final class Job implements WorkerJob {

        private final int id;
        private @Nullable ByteBuffer reply;
        /** The direct buffer holding the reply, returned to the pool on release. */
        private @Nullable ByteBuffer held;

        private boolean awaited;
        private boolean released;

        Job(int id) {
            this.id = id;
        }

        @Override
        public ByteBuffer await() {
            if (released) {
                throw new IllegalStateException("The job was released");
            }
            ByteBuffer answer = reply;
            if (answer == null) {
                answer = receive();
            }
            return answer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        }

        private ByteBuffer receive() {
            if (!hasReply(pool, id)) {
                IdleTimeExecutor.awaitOutside(() -> {
                    awaitReply(pool, id);
                    return null;
                });
            }
            awaited = true;
            inFlight--;
            int length = replyLength(pool, id);
            if (length < 0) {
                String error = replyError(pool, id);
                forget(pool, id);
                throw new WorkerException(error);
            }
            ByteBuffer buffer = replyBuffers.poll();
            if (buffer == null || buffer.capacity() < length) {
                buffer = ByteBuffer.allocateDirect(Math.max(length, 256 * 1024));
            }
            if (!copyReply(pool, id, replyView(length, false))) {
                copyReply(pool, id, replyView(length, true));
            }
            buffer.put(0, replyStaging, 0, length);
            forget(pool, id);
            held = buffer;
            ByteBuffer answer = buffer.slice(0, length).order(ByteOrder.LITTLE_ENDIAN);
            reply = answer;
            return answer;
        }

        @Override
        public boolean done() {
            return reply != null || hasReply(pool, id);
        }

        @Override
        public void release() {
            if (released) {
                return;
            }
            released = true;
            reply = null;
            ByteBuffer buffer = held;
            held = null;
            if (buffer != null) {
                replyBuffers.push(buffer);
            } else if (!awaited) {
                // Never awaited: the answer is dropped when it comes.
                inFlight--;
                discardWhenAnswered(pool, id);
            }
        }
    }

    // ---- The page's side, in JavaScript -----------------------------------------------------

    @JSBody(script = "return typeof Worker === 'function';")
    private static native boolean hasWorkers();

    @JSBody(script = "return (navigator.hardwareConcurrency | 0) || 2;")
    private static native int hardwareConcurrency();

    /** The program's file URL from the build manifest, or the development build's default name. */
    @JSBody(
            params = {"program", "part", "fallback"},
            script = "var build = window.incariBuild; var programs = build && build.programs;"
                    + " var entry = programs && programs[program];"
                    // The single-file page's own URL may have no folder (offline.ts).
                    + " var base = window.incariFiles ? window.incariFiles.base : location.href;"
                    + " return new URL((entry && entry[part]) || fallback, base).href;")
    private static native String programUrl(String program, String part, String fallback);

    @JSBody(
            params = {"source", "program", "count", "runtime", "module", "slotBytes", "slots"},
            script = "var url = URL.createObjectURL(new Blob([source], {type: 'text/javascript'}));"
                    + "var pool = {workers: [], inFlight: [], replies: new Map(), waiters: new Map(),"
                    + "  discard: new Set(), slotOf: new Map(), freeSlots: [], sab: null, slotBytes: slotBytes,"
                    + "  nextId: 1, failed: null};"
                    // Messages go through shared memory where the page is cross-origin isolated.
                    + "if (self.crossOriginIsolated && typeof SharedArrayBuffer === 'function') {"
                    + "  pool.sab = new SharedArrayBuffer(slotBytes * slots);"
                    + "  for (var s = 0; s < slots; s++) pool.freeSlots.push(s); }"
                    + "var answered = function(id, data) {"
                    + "  if (pool.slotOf.has(id)) { pool.freeSlots.push(pool.slotOf.get(id)); pool.slotOf.delete(id); }"
                    + "  if (pool.discard.delete(id)) return;"
                    + "  pool.replies.set(id, data);"
                    + "  var waiter = pool.waiters.get(id); if (waiter) { pool.waiters.delete(id); waiter(); } };"
                    + "pool.fail = function(reason) { if (pool.failed) return; pool.failed = reason;"
                    + "  console.error('[Incari] ' + program + ' workers failed: ' + reason);"
                    + "  var ids = Array.from(pool.waiters.keys()); ids.forEach(function(id) { answered(id, {id: id, error: reason}); }); };"
                    // A function expression called in the loop: TeaVM's minifier drops the parentheses
                    // around an immediately called one, which then reads as a declaration.
                    + "var startWorker = function(index) {"
                    + "  var worker = new Worker(url, {name: 'incari-' + program + '-' + index});"
                    + "  worker.onmessage = function(event) { var data = event.data;"
                    + "    if (data.kind === 'ready') return;"
                    + "    if (data.kind === 'failed') { pool.fail(data.error); return; }"
                    + "    pool.inFlight[index]--; answered(data.id, data); };"
                    + "  worker.onerror = function(event) { pool.fail('worker ' + index + ': ' + (event.message || 'error')); };"
                    + "  worker.postMessage({kind: 'start', runtime: runtime, module: module});"
                    + "  pool.workers.push(worker); pool.inFlight.push(0); };"
                    + "for (var i = 0; i < count; i++) startWorker(i);"
                    + "self.__incariWorkerPools = self.__incariWorkerPools || {};"
                    + "self.__incariWorkerPools[program] = pool;"
                    + "return pool;")
    private static native JSObject createPool(
            String source, String program, int count, String runtime, String module, int slotBytes, int slots);

    @JSBody(
            params = {"pool", "staging", "length"},
            script = "if (staging.byteLength === 0) return -1; var view = staging.subarray(0, length);"
                    + "var id = pool.nextId++; if (pool.failed) { pool.replies.set(id, {id: id, error: pool.failed}); return id; }"
                    + "var best = 0; for (var i = 1; i < pool.workers.length; i++) if (pool.inFlight[i] < pool.inFlight[best]) best = i;"
                    + "pool.inFlight[best]++;"
                    + "if (pool.sab && view.length <= pool.slotBytes && pool.freeSlots.length) {"
                    + "  var slot = pool.freeSlots.pop(); pool.slotOf.set(id, slot);"
                    + "  new Int8Array(pool.sab, slot * pool.slotBytes, view.length).set(view);"
                    + "  pool.workers[best].postMessage({id: id, buffer: pool.sab, offset: slot * pool.slotBytes, length: view.length});"
                    + "} else { var copy = view.slice();"
                    + "  pool.workers[best].postMessage({id: id, buffer: copy.buffer, offset: 0, length: view.length}, [copy.buffer]); }"
                    + "return id;")
    private static native int submit(JSObject pool, Int8Array staging, int length);

    @JSBody(
            params = {"pool", "staging", "length"},
            script = "if (staging.byteLength === 0) return false; var view = staging.subarray(0, length);"
                    + "pool.workers.forEach(function(worker) { var copy = view.slice();"
                    + "  worker.postMessage({id: -1, buffer: copy.buffer, offset: 0, length: view.length}, [copy.buffer]); });"
                    + "return true;")
    private static native boolean broadcast(JSObject pool, Int8Array staging, int length);

    @JSBody(
            params = {"pool", "id"},
            script = "return pool.replies.has(id);")
    private static native boolean hasReply(JSObject pool, int id);

    @JSBody(
            params = {"pool", "id"},
            script = "var data = pool.replies.get(id); return data && data.reply ? data.reply.byteLength : -1;")
    private static native int replyLength(JSObject pool, int id);

    @JSBody(
            params = {"pool", "id"},
            script = "var data = pool.replies.get(id); return data ? String(data.error) : 'no answer';")
    private static native String replyError(JSObject pool, int id);

    @JSBody(
            params = {"pool", "id", "view"},
            script = "if (view.byteLength === 0) return false;"
                    + "view.set(new Int8Array(pool.replies.get(id).reply)); return true;")
    private static native boolean copyReply(JSObject pool, int id, Int8Array view);

    @JSBody(
            params = {"pool", "id"},
            script = "pool.replies.delete(id);")
    private static native void forget(JSObject pool, int id);

    @JSBody(
            params = {"pool", "id"},
            script = "if (!pool.replies.delete(id)) pool.discard.add(id);")
    private static native void discardWhenAnswered(JSObject pool, int id);

    /** Suspends the calling coroutine until the job's answer is in. */
    @Async
    private static native Void awaitReply(JSObject pool, int id);

    private static void awaitReply(JSObject pool, int id, AsyncCallback<Void> callback) {
        onReply(pool, id, () -> callback.complete(null));
    }

    @JSFunctor
    interface Answered extends JSObject {
        void answered();
    }

    @JSBody(
            params = {"pool", "id", "callback"},
            script = "if (pool.replies.has(id)) { callback(); } else { pool.waiters.set(id, callback); }")
    private static native void onReply(JSObject pool, int id, Answered callback);
}
