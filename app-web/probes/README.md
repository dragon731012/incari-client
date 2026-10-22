# Probes

Small programs compiled alone with TeaVM against the browser build's class library and run in
headless Chromium, in seconds rather than the full build's ten minutes (`app-web/README.md`,
Experiments). Regression probes guard a fix in `tools/teavm-patches`, `tools/webbuild` or
`web-classlib` and print "probe passed". Measurements print the cost an ADR relied on; checks confirm a behaviour the build depends on.

```sh
app-web/probes/run.sh <Name> [Gradle options]   # for example -Pteavm.fast, -Pbrowser.gpu=webgpu
```

| Probe | Kind | What it checks |
| --- | --- | --- |
| `ArrayDequeProbe` | Regression | tools/teavm-patches' TArrayDeque fix |
| `ArrayInstanceOfProbe` | Regression | Via's StructuredItemRewriter |
| `ByteCopyProbe` | Measurement | How bytes cross between Java arrays and JavaScript buffers |
| `CoroutineCostProbe` | Measurement | What TeaVM's coroutine transformation costs ordinary code (ADR 0036) |
| `DeflateProbe` | Check | The build's deflate and inflate streams, used as the game writes chunks and NBT |
| `DirectAllocationProbe` | Regression | Heap growth (a tools/teavm-patches fix until TeaVM 0.16.0 had it) |
| `FetchFailureProbe` | Check | A failed download reported to a suspended coroutine is caught, and the page doesn't crash |
| `FileReadProbe` | Regression | web-classlib's FileInputStreamAdditions, and `RandomAccessFile.read` (fixed in TeaVM 0.16.0) |
| `ImmutableCollectionsProbe` | Regression | tools/teavm-patches' TTemplateCollections hashing (ADR 0034) |
| `InitOrderProbe` | Regression | tools/teavm-patches' ClassInitializerInsertionTransformer fix |
| `InterruptedWaitProbe` | Regression | tools/teavm-patches' TObject fix |
| `JspiProbe` | Check | Java threads behave the same in the Fast build (JSPI) as in the Compatible build |
| `MathProbe` | Check | tools/webbuild's `MathIntrinsics`: `Math.floor`, `ceil` and `sqrt` as WebAssembly instructions |
| `MessageTypeProbe` | Regression | tools/webbuild's TypeCaptures |
| `ModuleProbe` | Regression | web-classlib's `Module` and `ModuleLayer` |
| `PacketHandoffProbe` | Measurement | What handing a packet to the game's thread costs (ADR 0033) |
| `ReloadLeakProbe` | Check | Memory held across page reloads in one tab (read with a CDP script) |
| `SharableProbe` | Regression | tools/webbuild's RemovalTransformer |
| `StorageProbe` | Regression | platform-web's file system (ADR 0011) under TeaVM's own java.io and java.nio.file |
| `SuppressedProbe` | Regression | Suppressed exceptions (a tools/teavm-patches fix until TeaVM 0.16.0 had it) |
| `SuspendingReflectionProbe` | Regression | tools/teavm-patches' CoroutineTransformation fix for non-nullable locals |
| `SuspendingTernaryProbe` | Regression | A suspending `c ? a : b` (a tools/teavm-patches fix until TeaVM 0.16.0 had it) |
| `ThreadLocalProbe` | Check | web-classlib's `ThreadLocal` across thread switches |
| `ThrowCensusProbe` | Check | The throw census, `-Pteavm.throwCensus` (ADR 0033) |
| `ViaPipelineUtilProbe` | Regression | web-classlib's substitute for Via's `PipelineUtil` |
| `ViaTypeTokenProbe` | Regression | TypeCaptures' entry for Via's bundled Gson |
| `WaitRoutingProbe` | Regression | tools/webbuild's MonitorElision routing every `Object.wait` through web-classlib's `Waits` |
| `WeakQueueProbe` | Check | References polled from a ReferenceQueue keep their own class's methods |
| `WebGpuCallProbe` | Measurement | What one WebGPU call costs from Java (`docs/webgpu-backend.md`) |
| `WrapIntProbe` | Check | `int`s reach JavaScript as the same Numbers (tools/webbuild's `JsoIntrinsics`) |
| `ZipFileProbe` | Regression | tools/teavm-patches' TZipFile fix |
