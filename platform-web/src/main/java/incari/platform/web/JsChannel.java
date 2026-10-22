package incari.platform.web;

import incari.platform.api.threads.PlatformChannel;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * A {@link PlatformChannel} over a JavaScript object with four calls, the same at both ends: the
 * page's side of a game worker ({@link #startWorker}) and the worker's side of its page
 * ({@link #parent}, offered by app-web's {@code game-worker.ts}). {@code send} hands a buffer over,
 * {@code take} gives the next message if there is one, {@code next} a promise of it (null once the
 * channel ends), {@code close} ends it. A message only queues on the JavaScript side; a Java thread
 * waiting in {@link #receive} is resumed with it (ADR 0039).
 */
final class JsChannel implements PlatformChannel {

    private final JSObject channel;

    private JsChannel(JSObject channel) {
        this.channel = channel;
    }

    /**
     * Starts the game's program again in a module worker ({@code game-worker.js}), in the build the
     * page loaded, and returns the channel to it; null if the page doesn't know its build (an
     * experiment's page) or has no workers. In a game worker, a worker of its own, in its build.
     */
    static @Nullable JsChannel startWorker(String role) {
        JSObject channel = createWorker(role);
        return channel == null ? null : new JsChannel(channel);
    }

    /** In a game worker, the channel to the page that started it; null elsewhere. */
    static @Nullable JsChannel parent() {
        JSObject channel = parentObject();
        return channel == null ? null : new JsChannel(channel);
    }

    @Override
    public void send(int kind, byte[] bytes, int offset, int length) {
        byte[] part =
                offset == 0 && length == bytes.length ? bytes : Arrays.copyOfRange(bytes, offset, offset + length);
        sendBuffer(channel, kind, Int8Array.copyFromJavaArray(part).getBuffer());
    }

    @Override
    public @Nullable Message receive() {
        return message(next(channel));
    }

    @Override
    public @Nullable Message poll() {
        return message(take(channel));
    }

    @Override
    public void close() {
        closeChannel(channel);
    }

    private static @Nullable Message message(@Nullable JSObject envelope) {
        if (envelope == null) {
            return null;
        }
        return new Message(kindOf(envelope), new Int8Array(dataOf(envelope)).copyToJavaArray());
    }

    @Async
    private static native @Nullable JSObject next(JSObject channel);

    private static void next(JSObject channel, AsyncCallback<@Nullable JSObject> callback) {
        whenNext(channel, callback::complete);
    }

    @JSFunctor
    interface Received extends JSObject {
        void received(@Nullable JSObject envelope);
    }

    @JSBody(
            params = {"channel", "callback"},
            script = "channel.next().then(function(envelope) { callback(envelope); });")
    private static native void whenNext(JSObject channel, Received callback);

    @JSBody(params = "channel", script = "return channel.take();")
    private static native @Nullable JSObject take(JSObject channel);

    @JSBody(
            params = {"channel", "kind", "data"},
            script = "channel.send(kind, data);")
    private static native void sendBuffer(JSObject channel, int kind, ArrayBuffer data);

    @JSBody(params = "channel", script = "channel.close();")
    private static native void closeChannel(JSObject channel);

    @JSBody(params = "envelope", script = "return envelope.kind;")
    private static native int kindOf(JSObject envelope);

    @JSBody(params = "envelope", script = "return envelope.data;")
    private static native ArrayBuffer dataOf(JSObject envelope);

    @JSBody(script = "return self.__incariParent || null;")
    private static native @Nullable JSObject parentObject();

    /**
     * The page's side: a module worker running {@code game-worker.js} beside the page's scripts,
     * started with the build {@code boot.ts} recorded.
     */
    @JSBody(
            params = "role",
            script = "if (typeof window === 'undefined') {"
                    // A game worker's own workers (ADR 0042), through its game-worker.ts.
                    + "  return self.__incariStartWorker ? self.__incariStartWorker(role) : null; }"
                    + "var build = window.incariBuild && window.incariBuild.program;"
                    + "if (!build || typeof Worker === 'undefined') return null;"
                    + "var queue = [], waiting = null, ended = false;"
                    + "function push(envelope) { if (ended) return; if (envelope === null) ended = true;"
                    + "  if (waiting) { var resolve = waiting; waiting = null; resolve(envelope); }"
                    + "  else queue.push(envelope); }"
                    // The single-file page's folder where it has one: its own URL may have none (offline.ts).
                    + "var base = window.incariFiles ? window.incariFiles.base : document.baseURI;"
                    + "var worker = new Worker(new URL('game-worker.js', base),"
                    + "  {type: 'module', name: 'incari-' + role});"
                    + "worker.onmessage = function(event) { var d = event.data;"
                    + "  if (d.kind === -1) { push(null); return; }"
                    + "  push({kind: d.kind, data: d.data}); };"
                    + "worker.onerror = function(event) {"
                    + "  console.error('[Incari] The ' + role + ' worker failed: ' + (event.message || event)); push(null); };"
                    // A port of the storage worker's own (ADR 0041): the worker's file changes go
                    // straight to it, not through the page.
                    + "var storage = window.incariStorage && window.incariStorage.connect"
                    + "  ? window.incariStorage.connect() : null;"
                    + "worker.postMessage({start: {runtime: new URL(build.runtime, base).href,"
                    + "  module: new URL(build.module, base).href, jspi: build.jspi, role: role,"
                    + "  query: location.search}, storage: storage}, storage ? [storage] : []);"
                    + "return {"
                    + "  send: function(kind, data) { worker.postMessage({kind: kind, data: data}, [data]); },"
                    + "  take: function() { var head = queue[0]; if (head === undefined || head === null) return null;"
                    + "    queue.shift(); return head; },"
                    + "  next: function() { if (queue.length > 0) return Promise.resolve(queue.shift());"
                    + "    return new Promise(function(resolve) { waiting = resolve; }); },"
                    + "  close: function() { worker.postMessage({kind: -1, data: new ArrayBuffer(0)});"
                    + "    setTimeout(function() { worker.terminate(); }, 5000); push(null); } };")
    private static native @Nullable JSObject createWorker(String role);
}
