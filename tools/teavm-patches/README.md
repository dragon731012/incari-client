# tools/teavm-patches: fixes to TeaVM

Fixes to TeaVM 0.16.0 the browser build needs before a TeaVM release has them. Each file is TeaVM's
own source (Apache-2.0, header kept), with the change described in its header. `tools/webbuild`
puts this module ahead of TeaVM on its class path, and checks at start-up that these classes won.
Nothing here ships except through TeaVM's compiler.

## Fixes

All are worth reporting upstream (konsoletyper/teavm). Probes run with `app-web/probes/run.sh <Name>`;
tests run on the JVM.

| Class | Bug | What it broke | Check |
| --- | --- | --- | --- |
| `CoroutineTransformation` | Blocks around suspensions used non-null types for values restored as nullable | Module failed to validate (`BanListEntry.serialize`) | — |
| `CoroutineTransformation` | Non-nullable locals read before any set on the save path | "uninitialized non-defaultable local" in reflection callers | `SuspendingReflectionProbe` |
| `ClassInitializerInsertionTransformer` | Subclass initialized before its superclass (against JLS 12.4.2) | "Duplicate id value" creating the player | `InitOrderProbe` |
| `TObject` | Interrupted `wait()` didn't retake the monitor | Crash when joining a world (sound thread) | `InterruptedWaitProbe` |
| `TArrayDeque` | Iterator `remove()` skipped the shifted element; removal shifted in a loop | Crash on the first expired particle; slow removals | `ArrayDequeProbe` |
| `TZipFile` | Inflater got no trailing byte at an entry's end | 638 textures lost from a community pack | `ZipFileProbe` |
| `TTemplateCollections` | Empty immutable map divided by zero in `get()` | Crash entering the End | `EmptyTemplateMapTest` |
| `TTemplateCollections` | `Set.of`/`Map.of` lookups compared every element | Slow lookups (ADR 0034) | `ImmutableCollectionsProbe` |
| `TCollections` | `unmodifiableCollection` scanned every element for `contains` | Slow model discovery (sets and maps fixed in 0.16.0) | `UnmodifiableViewsTest` |
| `StringConcatFactorySubstitutor` | Concatenation called `toString` virtually | Every concatenation, so every exception, became a coroutine | — |
| `WasmGCReferenceQueueDependency` | Polled references typed as plain `WeakReference` | Null trap in Guava's weak maps (Optimize World) | `WeakQueueProbe` |
| `WeakReferenceIntrinsic` | `new WeakReference<>(null)` reached `new WeakRef(null)` | Uncatchable `TypeError` | — |
| `TPath` | `Path.of(URI)` failed for any `file:` URI but `file:///` | The game couldn't find its built-in packs | — |
| `ClassInitElimination` | Interface method calls counted as initializing the interface | A `catch` saw null for an interface constant | — |
| `TDefaultPath` | `resolve` from an empty path, or after the root | Listing resource packs | — |
| `TByteBufferImpl`, `TByteBufferNative` | Absolute `getLong(int)` moved the position | Every PNG read skipped 8 bytes | — |
| `TByteBuffer` | Absolute bulk `put` copied nothing when the source had no bytes after its position | Zero projection matrix; blank sprites | `AbsoluteBulkPutTest` |
| `TByteBuffer` | `allocateDirect` memory not zeroed | Region files read chunks from garbage offsets | — |
| `TFormatter` | `%%` and `%n` consumed arguments; `MIN_VALUE` printed `--` | F3 screen crashed | `FormatterTest` |
| `TSortedStreamImpl`, `TSimpleStreamImpl` | Sorted stream kept the source's size estimate | Tab list crashed in multiplayer | `StreamToArrayTest` |
| `WasmGCJsoCommonGenerator` | Functions given to JavaScript weren't exported | Binaryen's `--closed-world` deleted `main` (ADR 0034) | — |
| `WasmGCSupertypeFunctionGenerator` | `instanceof` and casts walked every tag range of a widely implemented interface | Slow type checks at start-up (ADR 0026) | — |
| `CoroutineFunctions`, `WasmGCDependencies` | 0.16.0's coroutines check the fiber with static `isPresentAndSuspending(fiber)`, twice the code of 0.15's `isSuspending()` and called after every call that may suspend; V8 then inlined less of the game | Compatible build 17% slower in the lobby at 4x throttle (ADR 0064) | — |
| `ReflectionMetadataGenerator`, `WasmGCModuleGenerator` | Reflection setup and start function were single huge functions | V8's optimizer ran out of memory and Chrome killed the tab (ADR 0042) | — |

## JSPI: the Fast build

Not a fix, but a second way to run threads (ADR 0036). TeaVM compiles 81% of this program as
coroutines, which run about 1.5x slower (`CoroutineCostProbe`). JSPI lets the browser keep the
suspended stack instead.

- `AsyncMethodFinder`: with `incari.jspi` set (only in a `--jspi` build), finds no async methods,
  so nothing is compiled as a coroutine.
- `Fiber`: adds `Fiber.Jspi`. Each fiber runs through `WebAssembly.promising`; `suspend` awaits a
  promise through a `WebAssembly.Suspending` import (`app-web`'s `jspi.ts`). `tools/webbuild`'s
  `JspiTransformer` routes `Fiber.suspend` and `Fiber.start` there.

Differences from coroutines:

- A woken thread resumes from a microtask, not inside the call that woke it.
- A JavaScript callback into Java still must not suspend (JSPI traps).
- V8 never frees a suspended stack. So on `pagehide`, `jspi.ts` resumes each waiting fiber with an
  order to end, and it unwinds by trapping, with no Java code run. Without this, every reload
  leaked the whole game.

`JspiProbe` checks threads behave the same in both builds.

## Fixed upstream

TeaVM 0.16.0 has these, so the patches went (ADR 0064): `WasmTypeInference`; `CoroutineTransformation`'s
type after a suspending `c ? a : b`; `TThrowable`'s suppressed array; `Heap` growth; `instanceof Item[]`
(`WasmGCInstructionGenerationVisitor`, `WasmGCSupertypeFunctionGenerator`); the absolute bulk `put` in
`TByteBufferImpl` and `TByteBufferNative`; `TCollections`' set and map views; `TDefaultPath`'s
`iterator` and `equals`. Their probes still run.

## Invariants

- One class per fix, as close to upstream as possible, the change described here and in the file.
- Drop a patch when the pinned TeaVM has the fix.

## Tests

```sh
./gradlew :tools:teavm-patches:check
```
