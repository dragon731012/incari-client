/*
 *  Copyright 2018 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Modified for Incari (tools/teavm-patches/README.md): adds Fiber.Jspi, fibers on WebAssembly
 *  JavaScript Promise Integration for builds that compile no coroutines.
 */
package org.teavm.runtime;

import java.util.Arrays;
import org.teavm.interop.Address;
import org.teavm.interop.AsyncCallback;
import org.teavm.interop.Export;
import org.teavm.interop.Import;
import org.teavm.interop.StaticInit;
import org.teavm.interop.Unmanaged;

@StaticInit
public final class Fiber {
    public static final int STATE_RUNNING = 0;
    public static final int STATE_SUSPENDING = 1;
    public static final int STATE_RESUMING = 2;
    public static int userThreadCount = 1;

    private int[] intValues;
    private int intTop;
    private long[] longValues;
    private int longTop;
    private float[] floatValues;
    private int floatTop;
    private double[] doubleValues;
    private int doubleTop;
    private Object[] objectValues;
    private int objectTop;
    private int state;
    private FiberRunner runner;
    private Object result;
    private Throwable exception;
    private boolean isPendingResume;
    private boolean daemon;

    private static Fiber current;
    private static PendingCall lastPendingCall;

    private Fiber(FiberRunner runner, boolean daemon) {
        this.runner = runner;
        this.daemon = daemon;
    }

    public void push(int value) {
        if (intValues == null) {
            intValues = new int[4];
        } else if (intTop + 1 == intValues.length) {
            intValues = Arrays.copyOf(intValues, intValues.length * 3 / 2);
        }
        intValues[intTop++] = value;
    }

    public void push(long value) {
        if (longValues == null) {
            longValues = new long[4];
        } else if (longTop + 1 == longValues.length) {
            longValues = Arrays.copyOf(longValues, longValues.length * 3 / 2);
        }
        longValues[longTop++] = value;
    }

    public void push(float value) {
        if (floatValues == null) {
            floatValues = new float[4];
        } else if (floatTop + 1 == floatValues.length) {
            floatValues = Arrays.copyOf(floatValues, floatValues.length * 3 / 2);
        }
        floatValues[floatTop++] = value;
    }

    public void push(double value) {
        if (doubleValues == null) {
            doubleValues = new double[4];
        } else if (doubleTop + 1 == doubleValues.length) {
            doubleValues = Arrays.copyOf(doubleValues, doubleValues.length * 3 / 2);
        }
        doubleValues[doubleTop++] = value;
    }

    public void push(Object value) {
        if (objectValues == null) {
            objectValues = new Object[4];
        } else if (objectTop + 1 == objectValues.length) {
            objectValues = Arrays.copyOf(objectValues, objectValues.length * 3 / 2);
        }
        objectValues[objectTop++] = value;
    }

    public static void reversePush(int value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(long value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(float value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(double value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(Object value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(PlatformObject value, Fiber fiber) {
        fiber.push(new PlatformObjectWrapper(value));
    }

    public static void reversePush(PlatformFunction value, Fiber fiber) {
        fiber.push(new PlatformFunctionWrapper(value));
    }

    public static void reversePush(PlatformException value, Fiber fiber) {
        fiber.push(new PlatformExceptionWrapper(value));
    }

    @Unmanaged
    public int popInt() {
        return intValues[--intTop];
    }

    @Unmanaged
    public long popLong() {
        return longValues[--longTop];
    }

    @Unmanaged
    public float popFloat() {
        return floatValues[--floatTop];
    }

    @Unmanaged
    public double popDouble() {
        return doubleValues[--doubleTop];
    }

    @Unmanaged
    public Object popObject() {
        Object result = objectValues[--objectTop];
        objectValues[objectTop] = null;
        return result;
    }

    public PlatformObject popPlatformObject() {
        var wrapper = (PlatformObjectWrapper) popObject();
        return wrapper.object;
    }

    public PlatformFunction popPlatformFunction() {
        var wrapper = (PlatformFunctionWrapper) popObject();
        return wrapper.object;
    }

    public PlatformException popPlatformException() {
        var wrapper = (PlatformExceptionWrapper) popObject();
        return wrapper.object;
    }

    @Unmanaged
    public static Fiber current() {
        return current;
    }

    @Unmanaged
    public boolean isSuspending() {
        return state == STATE_SUSPENDING;
    }

    @Unmanaged
    public static boolean isPresentAndSuspending(Fiber fiber) {
        return fiber != null && fiber.isSuspending();
    }

    @Unmanaged
    public boolean isResuming() {
        return state == STATE_RESUMING;
    }

    @Unmanaged
    public static boolean isPresentAndResuming(Fiber fiber) {
        return fiber != null && fiber.isResuming();
    }

    @Unmanaged
    public static boolean getBoolean(Object v) {
        return v != null ? (Boolean) v : false;
    }

    @Unmanaged
    public static byte getByte(Object v) {
        return v != null ? (Byte) v : 0;
    }

    @Unmanaged
    public static short getShort(Object v) {
        return v != null ? (Short) v : 0;
    }

    @Unmanaged
    public static int getInt(Object v) {
        return v != null ? (Integer) v : 0;
    }

    @Unmanaged
    public static char getChar(Object v) {
        return v != null ? (Character) v : 0;
    }

    @Unmanaged
    public static long getLong(Object v) {
        return v != null ? (Long) v : 0;
    }

    @Unmanaged
    public static float getFloat(Object v) {
        return v != null ? (Float) v : 0;
    }

    @Unmanaged
    public static double getDouble(Object v) {
        return v != null ? (Double) v : 0;
    }

    public static Object suspend(AsyncCall call) throws Throwable {
        Fiber fiber = current();
        if (fiber == null) {
            throw new IllegalStateException("Suspension point reached from non-threading context "
                    + "(perhaps, from native JS method). See https://teavm.org/docs/runtime/coroutines.html "
                    + "('Interaction with JavaScript' section)");
        }
        Thread javaThread = Thread.currentThread();
        if (fiber.isResuming()) {
            fiber.state = STATE_RUNNING;
            if (fiber.exception != null) {
                throw fiber.exception;
            }
            return fiber.result;
        }
        PendingCall pendingCall = new PendingCall(call, lastPendingCall);
        if (lastPendingCall != null) {
            lastPendingCall.next = pendingCall;
        }
        lastPendingCall = pendingCall;

        fiber.state = STATE_SUSPENDING;
        pendingCall.callback = new AsyncCallbackImpl(pendingCall, javaThread, fiber);
        call.run(pendingCall.callback);
        return null;
    }

    static class AsyncCallbackImpl implements AsyncCallback<Object> {
        PendingCall pendingCall;
        Thread javaThread;
        Fiber fiber;

        AsyncCallbackImpl(PendingCall pendingCall, Thread javaThread, Fiber fiber) {
            this.pendingCall = pendingCall;
            this.javaThread = javaThread;
            this.fiber = fiber;
        }

        @Override
        public void complete(Object result) {
            if (Fiber.current == this.fiber) {
                fiber.result = result;
                fiber.isPendingResume = true;
                removePendingCall();
            } else {
                setCurrentThread(javaThread);
                javaThread = null;
                Fiber fiber = this.fiber;
                this.fiber = null;
                fiber.result = result;
                removePendingCall();
                fiber.resume();
            }
        }

        @Override
        public void error(Throwable e) {
            if (Fiber.current == this.fiber) {
                fiber.exception = e;
                fiber.isPendingResume = true;
                removePendingCall();
            } else {
                setCurrentThread(javaThread);
                javaThread = null;
                Fiber fiber = this.fiber;
                this.fiber = null;
                fiber.exception = e;
                removePendingCall();
                fiber.resume();
            }
        }

        private void removePendingCall() {
            if (pendingCall.previous != null) {
                pendingCall.previous.next = pendingCall.next;
            }
            if (pendingCall.next != null) {
                pendingCall.next.previous = pendingCall.previous;
            }
            if (pendingCall == lastPendingCall) {
                lastPendingCall = pendingCall.previous;
            }
            pendingCall = null;
        }
    }

    static native void setCurrentThread(Thread thread);

    public static void start(FiberRunner runner, boolean daemon) {
        new Fiber(runner, daemon).start();
    }

    static void startMain(String[] args) {
        start(() -> runMain(args), false);
    }

    public static native void runMain(String[] args);

    private void start() {
        Fiber former = current;
        current = this;
        while (true) {
            runner.run();
            if (!isPendingResume) {
                break;
            }
            isPendingResume = false;
            state = STATE_RESUMING;
        }
        current = former;
        if (!isSuspending() && !daemon && --userThreadCount == 0) {
            EventQueue.stop();
        }
    }

    void resume() {
        state = STATE_RESUMING;
        start();
    }

    public interface FiberRunner {
        void run();
    }

    public interface AsyncCall {
        void run(AsyncCallback<?> callback);
    }

    static class PendingCall {
        AsyncCall value;
        PendingCall next;
        PendingCall previous;
        AsyncCallbackImpl callback;

        PendingCall(AsyncCall value, PendingCall previous) {
            this.value = value;
            this.next = null;
            this.previous = previous;
        }
    }

    public static class PlatformObjectWrapper {
        public final PlatformObject object;

        PlatformObjectWrapper(PlatformObject object) {
            this.object = object;
        }
    }

    public static class PlatformFunctionWrapper {
        public final PlatformFunction object;

        PlatformFunctionWrapper(PlatformFunction object) {
            this.object = object;
        }
    }

    public static class PlatformExceptionWrapper {
        public final PlatformException object;

        PlatformExceptionWrapper(PlatformException object) {
            this.object = object;
        }
    }

    public static class PlatformObject {
    }

    public static class PlatformFunction {
    }

    public static class PlatformException {
    }

    // Incari: fibers on WebAssembly JavaScript Promise Integration (JSPI). A build with the
    // system property incari.jspi set compiles no method as a coroutine (AsyncMethodFinder), and
    // routes suspend and start here (tools/webbuild's JspiTransformer). Each fiber runs in its own
    // call of the module's "teavm.jspi.run" export through WebAssembly.promising, and suspends by
    // calling the "teavmJspi.await" import, wrapped in WebAssembly.Suspending, which hands the
    // engine a promise: the engine keeps the fiber's whole stack and returns to whoever started
    // the fiber, and resumes the stack once the promise settles. Nothing here is reachable
    // without that routing, so other builds import nothing from teavmJspi.
    public static final class Jspi {
        private static FiberRunner[] runners = new FiberRunner[16];
        private static boolean[] daemons = new boolean[16];
        private static int[] freeIds = new int[16];
        private static int freeCount;
        private static int nextId;

        private Jspi() {
        }

        public static void start(FiberRunner runner, boolean daemon) {
            int id;
            if (freeCount > 0) {
                id = freeIds[--freeCount];
            } else {
                id = nextId++;
                if (id == runners.length) {
                    runners = Arrays.copyOf(runners, id * 2);
                    daemons = Arrays.copyOf(daemons, id * 2);
                    freeIds = Arrays.copyOf(freeIds, id * 2);
                }
            }
            runners[id] = runner;
            daemons[id] = daemon;
            Thread former = Thread.currentThread();
            // Returns when the fiber ends or first suspends.
            runFiber(id);
            setCurrentThread(former);
        }

        @Export(name = "teavm.jspi.run")
        public static void run(int id) {
            FiberRunner runner = runners[id];
            boolean daemon = daemons[id];
            runners[id] = null;
            freeIds[freeCount++] = id;
            runner.run();
            if (!daemon && --userThreadCount == 0) {
                EventQueue.stop();
            }
        }

        public static Object suspend(AsyncCall call) throws Throwable {
            Thread javaThread = Thread.currentThread();
            Callback callback = new Callback();
            callback.promise = createPromise();
            call.run(callback);
            if (!callback.done) {
                // Completed later, from a callback: wait for it with the stack kept.
                callback.waiting = true;
                if (awaitPromise(callback.promise) != 0) {
                    // The page is going away. The engine never frees a suspended stack, nor
                    // anything it reaches (the whole program), so every fiber is resumed to end
                    // here: a trap, which no Java handler can catch, unwinds the fiber's stack.
                    Address.fromInt(-1).getInt();
                }
                setCurrentThread(javaThread);
            } else {
                dropPromise(callback.promise);
            }
            if (callback.exception != null) {
                throw callback.exception;
            }
            return callback.result;
        }

        static final class Callback implements AsyncCallback<Object> {
            int promise;
            boolean done;
            boolean waiting;
            Object result;
            Throwable exception;

            @Override
            public void complete(Object result) {
                if (done) {
                    return;
                }
                done = true;
                this.result = result;
                if (waiting) {
                    resolvePromise(promise);
                }
            }

            @Override
            public void error(Throwable e) {
                if (done) {
                    return;
                }
                done = true;
                exception = e;
                if (waiting) {
                    resolvePromise(promise);
                }
            }
        }

        @Import(module = "teavmJspi", name = "create")
        private static native int createPromise();

        /** Waits for the promise; nonzero when the page is going away and the fiber must end. */
        @Import(module = "teavmJspi", name = "await")
        private static native int awaitPromise(int promise);

        @Import(module = "teavmJspi", name = "resolve")
        private static native void resolvePromise(int promise);

        @Import(module = "teavmJspi", name = "drop")
        private static native void dropPromise(int promise);

        @Import(module = "teavmJspi", name = "run")
        private static native void runFiber(int id);
    }
}
