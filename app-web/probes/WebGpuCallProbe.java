import org.teavm.interop.Async;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Uint32Array;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLUniformLocation;
import org.teavm.jso.webgpu.GPUBindGroup;
import org.teavm.jso.webgpu.GPUDevice;
import org.teavm.jso.webgpu.GPURenderPassEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * What one WebGPU call costs from Java (docs/webgpu-backend.md, the W0 spike's open question):
 * the same frame of draws, each with its own uniform (a dynamic offset), encoded five ways and timed
 * on the CPU. Through TeaVM's JSO, a call per command, as a Java backend would; in one JavaScript
 * loop, the floor any language could reach; Java writing commands into linear memory and one
 * JavaScript call replaying them, the TypeScript layer a backend could fall back on; a render bundle
 * recorded once; and WebGL2's uniform and draw per object through JSO, as {@code render-webgl2}
 * draws what it doesn't multi-draw. Needs a WebGPU adapter: {@code app-web/probes/run.sh
 * WebGpuCallProbe -Pbrowser.gpu=webgpu} (Linux Chrome gives WebGPU only on Vulkan). Prints
 * microseconds a draw and "probe passed".
 */
public final class WebGpuCallProbe {

    private static final int DRAWS = 2000;

    private static final int WARMUP_FRAMES = 20;

    private static final int FRAMES = 100;

    /** Command codes for the replay route: one int code, then its arguments. */
    private static final int SET_BIND_GROUP = 1;

    private static final int DRAW_INDEXED = 2;

    private WebGpuCallProbe() {}

    /**
     * TeaVM's render pass, plus the dynamic-offset overload that takes a range of the offsets array,
     * which TeaVM's binding lacks: without it each draw would allocate a subarray.
     */
    interface Pass extends GPURenderPassEncoder {
        void setBindGroup(int index, GPUBindGroup group, Uint32Array offsets, int start, int length);
    }

    /** What {@link #setUp} made: the pipeline, buffers, bind group and target, and a recorded bundle. */
    interface Scene extends JSObject {
        @JSProperty
        GPUBindGroup bindGroup();

        @JSProperty
        Uint32Array offsets();

        @JSProperty
        WebGL2RenderingContext gl();

        @JSProperty
        WebGLUniformLocation glOffset();
    }

    @JSFunctor
    interface Done extends JSObject {
        void done(JSObject value);
    }

    public static void main(String[] args) {
        GPUDevice device = (GPUDevice) requestDevice();
        if (device == null) {
            System.out.println("probe failed: no WebGPU adapter (" + adapterNote() + ")");
            return;
        }
        System.out.println("WebGpuCallProbe: adapter " + adapterNote() + ", " + DRAWS + " draws a frame, "
                + FRAMES + " frames");
        Scene scene = setUp(device, DRAWS);
        IntBuffer commands = ByteBuffer.allocateDirect(DRAWS * 8 * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        // An Int32Array over the same linear memory, made once: the replay reads it in place.
        Int32Array commandView = Int32Array.fromJavaBuffer(commands);

        String[] names = {"JSO, a call per command", "one JavaScript loop", "replayed from linear memory",
            "render bundle", "WebGL2 through JSO"};
        double[] totals = new double[names.length];
        for (int frame = 0; frame < WARMUP_FRAMES + FRAMES; frame++) {
            for (int route = 0; route < names.length; route++) {
                double start = now();
                switch (route) {
                    case 0 -> {
                        Pass pass = beginPass(device, scene);
                        GPUBindGroup group = scene.bindGroup();
                        Uint32Array offsets = scene.offsets();
                        for (int i = 0; i < DRAWS; i++) {
                            pass.setBindGroup(0, group, offsets, i, 1);
                            pass.drawIndexed(3, 1, 0, 0, 0);
                        }
                        endPass(device, scene);
                    }
                    case 1 -> {
                        Pass pass = beginPass(device, scene);
                        drawAllInJs(pass, scene, DRAWS);
                        endPass(device, scene);
                    }
                    case 2 -> {
                        Pass pass = beginPass(device, scene);
                        commands.clear();
                        for (int i = 0; i < DRAWS; i++) {
                            commands.put(SET_BIND_GROUP).put(i);
                            commands.put(DRAW_INDEXED).put(3).put(0).put(0);
                        }
                        replay(pass, scene, commandView, commands.position());
                        endPass(device, scene);
                    }
                    case 3 -> {
                        Pass pass = beginPass(device, scene);
                        executeBundle(pass, scene);
                        endPass(device, scene);
                    }
                    default -> {
                        WebGL2RenderingContext gl = scene.gl();
                        WebGLUniformLocation offset = scene.glOffset();
                        prepareGl(scene);
                        for (int i = 0; i < DRAWS; i++) {
                            gl.uniform4f(offset, (i % 50) * 0.04f - 1, (i / 50) * 0.04f - 1, 0, 0);
                            gl.drawElements(WebGL2RenderingContext.TRIANGLES, 3, WebGL2RenderingContext.UNSIGNED_SHORT, 0);
                        }
                        gl.flush();
                    }
                }
                double elapsed = now() - start;
                if (frame >= WARMUP_FRAMES) {
                    totals[route] += elapsed;
                }
                // Untimed: let the GPU catch up, so the next frame's calls don't wait on a full queue.
                if (route == names.length - 1) {
                    glFinish(scene);
                } else {
                    workDone(device);
                }
            }
        }
        for (int route = 0; route < names.length; route++) {
            double frameMillis = totals[route] / FRAMES;
            System.out.println(String.format("  %-28s %7.3f ms a frame, %6.3f us a draw", names[route], frameMillis,
                    frameMillis * 1000 / DRAWS));
        }
        String error = takeError(scene);
        System.out.println(error == null ? "probe passed" : "probe failed: " + error);
    }

    @Async
    private static native JSObject requestDevice();

    private static void requestDevice(org.teavm.interop.AsyncCallback<JSObject> callback) {
        requestDevice(value -> callback.complete(value));
    }

    @JSBody(
            params = "done",
            script = "if (!navigator.gpu) { done(null); return; }"
                    + "navigator.gpu.requestAdapter().then(function(a) {"
                    + "  if (!a) return null;"
                    + "  globalThis.probeAdapter = a.info.vendor + '/' + a.info.architecture"
                    + "    + (a.info.isFallbackAdapter ? ' (fallback)' : '');"
                    + "  return a.requestDevice();"
                    + "}).then(function(d) { done(d || null); }, function() { done(null); });")
    private static native void requestDevice(Done done);

    @JSBody(script = "return globalThis.probeAdapter || (navigator.gpu ? 'none' : 'no navigator.gpu');")
    private static native String adapterNote();

    @Async
    private static native JSObject workDone(GPUDevice device);

    private static void workDone(GPUDevice device, org.teavm.interop.AsyncCallback<JSObject> callback) {
        workDone(device, value -> callback.complete(value));
    }

    @JSBody(
            params = {"device", "done"},
            script = "device.queue.onSubmittedWorkDone().then(function() { done(null); });")
    private static native void workDone(GPUDevice device, Done done);

    /**
     * A pipeline drawing one small triangle per draw, moved by a uniform read at a dynamic offset
     * (256 bytes apart, WebGPU's minimum); a 256x256 target; the same draws recorded as a bundle;
     * and a WebGL2 program doing the same on an OffscreenCanvas.
     */
    @JSBody(
            params = {"device", "draws"},
            script = "var s = {};"
                    + "var code = 'struct U { offset: vec4f };'"
                    + " + '@group(0) @binding(0) var<uniform> u: U;'"
                    + " + '@vertex fn vs(@location(0) p: vec2f) -> @builtin(position) vec4f {'"
                    + " + '  return vec4f(p * 0.02 + u.offset.xy, 0.0, 1.0); }'"
                    + " + '@fragment fn fs() -> @location(0) vec4f { return vec4f(1.0, 0.5, 0.0, 1.0); }';"
                    + "device.pushErrorScope('validation');"
                    + "var module = device.createShaderModule({ code: code });"
                    + "var layout = device.createBindGroupLayout({ entries: [{ binding: 0,"
                    + "  visibility: GPUShaderStage.VERTEX, buffer: { type: 'uniform', hasDynamicOffset: true } }] });"
                    + "s.pipeline = device.createRenderPipeline({"
                    + "  layout: device.createPipelineLayout({ bindGroupLayouts: [layout] }),"
                    + "  vertex: { module: module, entryPoint: 'vs', buffers: [{ arrayStride: 8,"
                    + "    attributes: [{ shaderLocation: 0, offset: 0, format: 'float32x2' }] }] },"
                    + "  fragment: { module: module, entryPoint: 'fs', targets: [{ format: 'rgba8unorm' }] },"
                    + "  primitive: { topology: 'triangle-list' } });"
                    + "var uniforms = new Float32Array(draws * 64);"
                    + "for (var i = 0; i < draws; i++) {"
                    + "  uniforms[i * 64] = (i % 50) * 0.04 - 1; uniforms[i * 64 + 1] = Math.floor(i / 50) * 0.04 - 1; }"
                    + "var ubuf = device.createBuffer({ size: uniforms.byteLength,"
                    + "  usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST });"
                    + "device.queue.writeBuffer(ubuf, 0, uniforms);"
                    + "s.bindGroup = device.createBindGroup({ layout: layout,"
                    + "  entries: [{ binding: 0, resource: { buffer: ubuf, size: 16 } }] });"
                    + "s.offsets = new Uint32Array(draws);"
                    + "for (var i = 0; i < draws; i++) s.offsets[i] = i * 256;"
                    + "s.vbuf = device.createBuffer({ size: 24, usage: GPUBufferUsage.VERTEX | GPUBufferUsage.COPY_DST });"
                    + "device.queue.writeBuffer(s.vbuf, 0, new Float32Array([0, 0, 1, 0, 0, 1]));"
                    + "s.ibuf = device.createBuffer({ size: 8, usage: GPUBufferUsage.INDEX | GPUBufferUsage.COPY_DST });"
                    + "device.queue.writeBuffer(s.ibuf, 0, new Uint16Array([0, 1, 2, 0]));"
                    + "s.view = device.createTexture({ size: [256, 256], format: 'rgba8unorm',"
                    + "  usage: GPUTextureUsage.RENDER_ATTACHMENT }).createView();"
                    + "var b = device.createRenderBundleEncoder({ colorFormats: ['rgba8unorm'] });"
                    + "b.setPipeline(s.pipeline); b.setVertexBuffer(0, s.vbuf); b.setIndexBuffer(s.ibuf, 'uint16');"
                    + "for (var i = 0; i < draws; i++) { b.setBindGroup(0, s.bindGroup, s.offsets, i, 1);"
                    + "  b.drawIndexed(3, 1, 0, 0, 0); }"
                    + "s.bundle = b.finish();"
                    + "device.popErrorScope().then(function(e) { if (e) s.error = e.message; });"
                    + "device.onuncapturederror = function(e) { s.error = s.error || e.error.message; };"
                    + "var gl = new OffscreenCanvas(256, 256).getContext('webgl2');"
                    + "function shader(type, src) { var sh = gl.createShader(type); gl.shaderSource(sh, src);"
                    + "  gl.compileShader(sh); return sh; }"
                    + "var p = gl.createProgram();"
                    + "gl.attachShader(p, shader(gl.VERTEX_SHADER, '#version 300 es\\nin vec2 p; uniform vec4 offset;'"
                    + "  + ' void main() { gl_Position = vec4(p * 0.02 + offset.xy, 0.0, 1.0); }'));"
                    + "gl.attachShader(p, shader(gl.FRAGMENT_SHADER, '#version 300 es\\nprecision mediump float;'"
                    + "  + ' out vec4 c; void main() { c = vec4(1.0, 0.5, 0.0, 1.0); }'));"
                    + "gl.bindAttribLocation(p, 0, 'p'); gl.linkProgram(p);"
                    + "if (!gl.getProgramParameter(p, gl.LINK_STATUS)) s.error = 'WebGL2 link: ' + gl.getProgramInfoLog(p);"
                    + "s.glProgram = p; s.glOffset = gl.getUniformLocation(p, 'offset');"
                    + "var vao = gl.createVertexArray(); gl.bindVertexArray(vao);"
                    + "var vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb);"
                    + "gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1]), gl.STATIC_DRAW);"
                    + "gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);"
                    + "var ib = gl.createBuffer(); gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ib);"
                    + "gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array([0, 1, 2, 0]), gl.STATIC_DRAW);"
                    + "s.gl = gl;"
                    + "return s;")
    private static native Scene setUp(GPUDevice device, int draws);

    @JSBody(
            params = {"device", "s"},
            script = "s.encoder = device.createCommandEncoder();"
                    + "var pass = s.encoder.beginRenderPass({ colorAttachments: [{ view: s.view,"
                    + "  loadOp: 'clear', storeOp: 'store', clearValue: [0, 0, 0, 1] }] });"
                    + "pass.setPipeline(s.pipeline); pass.setVertexBuffer(0, s.vbuf); pass.setIndexBuffer(s.ibuf, 'uint16');"
                    + "s.pass = pass; return pass;")
    private static native Pass beginPass(GPUDevice device, Scene scene);

    /** Ends the pass {@link #beginPass} began and submits its commands. */
    @JSBody(
            params = {"device", "s"},
            script = "s.pass.end(); device.queue.submit([s.encoder.finish()]);")
    private static native void endPass(GPUDevice device, Scene scene);

    @JSBody(
            params = {"pass", "s", "draws"},
            script = "var g = s.bindGroup, o = s.offsets;"
                    + "for (var i = 0; i < draws; i++) { pass.setBindGroup(0, g, o, i, 1); pass.drawIndexed(3, 1, 0, 0, 0); }")
    private static native void drawAllInJs(Pass pass, Scene scene, int draws);

    @JSBody(
            params = {"pass", "s", "view", "length"},
            script = "var g = s.bindGroup, o = s.offsets, i = 0;"
                    + "while (i < length) {"
                    + "  var op = view[i];"
                    + "  if (op === 1) { pass.setBindGroup(0, g, o, view[i + 1], 1); i += 2; }"
                    + "  else { pass.drawIndexed(view[i + 1], 1, view[i + 2], view[i + 3], 0); i += 4; } }")
    private static native void replay(Pass pass, Scene scene, Int32Array view, int length);

    @JSBody(params = {"pass", "s"}, script = "pass.executeBundles([s.bundle]);")
    private static native void executeBundle(Pass pass, Scene scene);

    @JSBody(
            params = "s",
            script = "var gl = s.gl; gl.useProgram(s.glProgram); gl.viewport(0, 0, 256, 256);"
                    + "gl.clear(gl.COLOR_BUFFER_BIT);")
    private static native void prepareGl(Scene scene);

    @JSBody(params = "s", script = "s.gl.finish();")
    private static native void glFinish(Scene scene);

    @JSBody(
            params = "s",
            script = "var e = s.error || null; if (!e && s.gl.getError() !== 0) e = 'WebGL2 error ' + s.gl.getError();"
                    + " return e;")
    private static native String takeError(Scene scene);

    @JSBody(script = "return performance.now();")
    private static native double now();
}
