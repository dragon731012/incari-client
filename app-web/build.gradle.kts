import java.util.Properties

plugins {
    java
}

// The browser build: the game compiled by TeaVM (WebAssembly GC) through platform-web.
// `./gradlew :app-web:teavm` writes build/teavm/, which is never committed or published.

dependencies {
    implementation(project(":client"))
    implementation(project(":platform-web"))
    // The WebGL2 graphics backend.
    implementation(project(":render-webgl2"))
    // Compiled into the browser build in place of the JDK parts TeaVM lacks. ClientJar also
    // names the directory its class-path resources live in.
    implementation(project(":web-classlib"))
    // java.time, which TeaVM maps onto ThreeTen Backport.
    runtimeOnly(libs.threetenbp)
    compileOnly(libs.teavm.jso)
    compileOnly(libs.teavm.jso.apis)
    compileOnly(libs.teavm.interop)

    // MemoryParityTest: the game's memory users over LWJGL and over web-classlib, on a JVM.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.asm)
    testImplementation(libs.asm.commons)
    testRuntimeOnly(libs.junit.launcher)
}

// The parity test runs the vanilla side on real LWJGL, so it needs this machine's natives.
val testNativeJars = run {
    val root = rootProject.layout.projectDirectory.asFile
    val version = Properties().apply {
        rootProject.layout.projectDirectory.file("incari.properties").asFile.reader().use { load(it) }
    }.getProperty("minecraft.version")
    providers.fileContents(rootProject.layout.projectDirectory.file("work/cache/versions/$version/natives.txt"))
        .asText.map { text -> text.lines().filter { it.isNotBlank() }.map { root.resolve(it.trim()) } }
        .orElse(emptyList())
}

tasks.test {
    classpath += files(testNativeJars)
    maxHeapSize = "2g"
    // Game classes write logs/ into the working directory; keep that in build/.
    val runDir = layout.buildDirectory.dir("test-run").get().asFile
    workingDir = runDir
    doFirst { runDir.mkdirs() }
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=" +
            rootProject.layout.projectDirectory.dir("work/natives/lwjgl").asFile.path,
    )
    testLogging { showStandardStreams = true }
}

val webbuildTool: Configuration = configurations.create("webbuildTool")
val shaderTool: Configuration = configurations.create("shaderTool")
val spriteTool: Configuration = configurations.create("spriteTool")

dependencies {
    webbuildTool(project(":tools:webbuild"))
    shaderTool(project(":tools:shaders"))
    spriteTool(project(":tools:sprites"))
}

val repoRoot = rootProject.layout.projectDirectory

// The client jar `./incari setup` downloaded. The dev server hands it to the page, which unpacks the
// game's resources from it (the build compiles classes only); it is never copied into the build.
val minecraftVersion = providers.fileContents(repoRoot.file("incari.properties")).asText.map { text ->
    Properties().apply { load(text.reader()) }.getProperty("minecraft.version")
}
val clientJar = minecraftVersion.map { repoRoot.file("work/cache/versions/$it/client.jar").asFile.path }

// The asset index `./incari assets` downloaded, and its objects (ADR 0013): the page lists the
// sounds from the index and fetches each one the first time it plays. Mojang's files, served from
// work/ by the dev server, never copied into the build. Without them the page runs silent.
val assetServerArgs: Provider<List<String>> = minecraftVersion.map { version ->
    val versionJson = repoRoot.file("work/cache/versions/$version/$version.json").asFile
    if (!versionJson.isFile) {
        emptyList()
    } else {
        @Suppress("UNCHECKED_CAST")
        val index = (groovy.json.JsonSlurper().parse(versionJson) as Map<String, Any?>)["assetIndex"] as Map<String, Any?>
        listOf(
            "--file", "asset-index.json=" + repoRoot.file("work/cache/assets/indexes/${index["id"]}.json").asFile.path,
            "--dir", "assets/objects=" + repoRoot.dir("work/cache/assets/objects").asFile.path,
        )
    }
}

// The game's shaders translated to GLSL ES 3.00 for the WebGL2 backend (tools/shaders). Translated
// Mojang files: build output beside the page, never inside build/teavm/ (so they don't invalidate the
// ten-minute compile) and never committed or published. The dev server serves them at /shaders.json.
val shaderManifest = layout.buildDirectory.file("web-shaders/shaders.json")

tasks.register<JavaExec>("shaders") {
    group = "build"
    description = "Translates the game's shaders for WebGL2 into build/web-shaders/shaders.json."
    classpath = shaderTool
    mainClass = "incari.tools.shaders.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val jar = clientJar.get()
    val version = minecraftVersion.get()
    // CI installs glslang and must check with it; locally it's used when it's installed.
    val requireGlslang = providers.environmentVariable("CI").isPresent ||
        providers.gradleProperty("shaders.requireGlslang").isPresent
    // A local: a lambda capturing the script can't be stored in the configuration cache.
    val manifest = shaderManifest
    inputs.file(jar)
    inputs.files(shaderTool)
    outputs.dir(manifest.map { it.asFile.parentFile })
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=${repoRoot.dir("work/natives/lwjgl").asFile}",
    )
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--client-jar", jar, "--minecraft", version, "--out", manifest.get().asFile.path) +
            (if (requireGlslang) listOf("--require-glslang") else emptyList())
    })
}

// The vanilla sprites decoded, with their mip levels, by the game's own code (tools/sprites,
// ADR 0026): the asset pack carries them, and the page makes a vanilla sprite from them instead of
// decoding its PNG. Decoded Mojang textures: build output, never committed or published.
val spriteFile = layout.buildDirectory.file("web-sprites/sprites.bin")

tasks.register<JavaExec>("sprites") {
    group = "build"
    description = "Decodes the vanilla sprites and their mip levels into build/web-sprites/sprites.bin."
    classpath = spriteTool
    mainClass = "incari.tools.sprites.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val jar = clientJar.get()
    val file = spriteFile
    inputs.file(jar)
    inputs.files(spriteTool)
    outputs.file(file)
    // The game's classes write logs/ into the working directory; keep that in build/.
    val runDir = layout.buildDirectory.dir("web-sprites/run").get().asFile
    workingDir = runDir
    doFirst { runDir.mkdirs() }
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=${repoRoot.dir("work/natives/lwjgl").asFile}",
    )
    argumentProviders.add(CommandLineArgumentProvider { listOf("--out", file.get().asFile.path) })
}

// The game has two builds (ADR 0036), both WebAssembly GC: Fast (JSPI) in build/teavm-fast/ and
// Compatible (TeaVM's coroutines) in build/teavm/. `-Pteavm.fast` makes the teavm task compile Fast,
// and the checks and the benchmark run it; without it, Compatible. The page offers whichever are built.
val fastBuild = providers.gradleProperty("teavm.fast").isPresent
val gameBuildDir = if (fastBuild) "teavm-fast" else "teavm"
/** The build the checks and the benchmark ask the page for (PageQuery): fast or compatible. */
val gameBuild = if (fastBuild) "fast" else "compatible"

tasks.register<JavaExec>("teavm") {
    group = "build"
    description = "Compiles the Compatible build with TeaVM into build/teavm/ (-Pteavm.fast: Fast, into build/teavm-fast/)."
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    maxHeapSize = "20g"
    val libraries = repoRoot.dir("work/cache/libraries").asFile
    // Incari's classes and the game as :client compiled it; the tool adds the Mojang libraries
    // tools/survey/rules.txt keeps. Mojang's other jars (LWJGL, JNA...) stay out on purpose.
    val appClasspath = sourceSets.main.get().runtimeClasspath.filter { !it.startsWith(libraries) }
    // As an input, the classpath also makes Gradle build the jars on it first.
    inputs.files(appClasspath)
    // For experiments: `-Pteavm.main=<class> -Pteavm.classpath=<dir>` compiles a small program
    // against the same class library in seconds, instead of the game in minutes.
    val mainClass = providers.gradleProperty("teavm.main").orElse("incari.app.web.WebMain")
    val extraClasspath = providers.gradleProperty("teavm.classpath").orElse("")
    // `-Pteavm.release`: TeaVM's ADVANCED optimizations, obfuscated, no debug information.
    // `-Pteavm.names` (with release): Java names kept in the module, for readable CPU profiles.
    // Binaryen's wasm-opt over the module (ADR 0034, 0043): on by default for the Fast release build
    // (under a minute; `-Pteavm.noWasmOpt` leaves it out), and for the Compatible one only with
    // `-Pteavm.wasmOpt` (a quarter of an hour and 10 GB more, not measured with ADR 0043's flags).
    val release = providers.gradleProperty("teavm.release").isPresent
    val names = providers.gradleProperty("teavm.names").isPresent
    val wasmOpt = release && !providers.gradleProperty("teavm.noWasmOpt").isPresent &&
        (fastBuild || providers.gradleProperty("teavm.wasmOpt").isPresent)
    inputs.property("wasmOpt", wasmOpt)
    // `-Pteavm.fast`: the Fast build, which suspends through JSPI instead of coroutines
    // (tools/webbuild's JspiTransformer, ADR 0036), into build/teavm-fast/.
    val jspi = fastBuild
    inputs.property("jspi", jspi)
    inputs.property("release", release)
    inputs.property("names", names)
    // Experiments build into their own directory, so they never replace the game's build.
    val output = layout.buildDirectory.dir(
        providers.gradleProperty("teavm.main").map { "teavm-experiment" }
            .orElse(gameBuildDir))
    // A development page that loads the module; `./gradlew :app-web:serve` serves the directory.
    val page = layout.projectDirectory.file("src/main/web/index.html")
    inputs.file(page)
    inputs.property("mainClass", mainClass)
    inputs.property("extraClasspath", extraClasspath)
    // The experiment's classes themselves, so editing a probe recompiles it.
    inputs.files(extraClasspath.map { path -> if (path.isEmpty()) emptyList() else listOf(File(path)) })
    outputs.dir(output)
    val verbose = providers.gradleProperty("teavm.verbose").map { true }.orElse(false)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--main", mainClass.get(),
            "--classpath", (appClasspath.files.map { it.path } + extraClasspath.get())
                .filter { it.isNotEmpty() }.joinToString(File.pathSeparator),
            "--out", output.get().asFile.path,
            "--page", page.asFile.path,
        ) + (if (verbose.get()) listOf("--verbose") else emptyList()) +
            (if (release) listOf("--release") else emptyList()) +
            (if (names) listOf("--names") else emptyList()) +
            (if (wasmOpt) listOf("--wasm-opt") else emptyList()) +
            (if (jspi) listOf("--jspi") else emptyList())
    })
    systemProperty("incari.cwd", repoRoot.asFile.path)
    // `-Pteavm.explainAsync=<method prefix>,...`: explain in async.txt why methods suspend.
    systemProperty("incari.explainAsync", providers.gradleProperty("teavm.explainAsync").orElse("").get())
    // `-Pteavm.throwCensus`: count every throw by site and thread, logged every 10 s (ADR 0033).
    val throwCensus = providers.gradleProperty("teavm.throwCensus").isPresent
    inputs.property("throwCensus", throwCensus)
    systemProperty("incari.throwCensus", throwCensus.toString())
    // `-Pteavm.noJsoIntrinsics`: ints to JavaScript through TeaVM's import again (tools/webbuild's
    // JsoIntrinsics), to compare.
    val jsoIntrinsics = !providers.gradleProperty("teavm.noJsoIntrinsics").isPresent
    inputs.property("jsoIntrinsics", jsoIntrinsics)
    systemProperty("incari.jsoIntrinsics", jsoIntrinsics.toString())
}

// ---- The mesher workers' program ------------------------------------------------------------
// Incari's terrain mesher on its own (incari.app.web.mesher.MesherWorker and the :mesher module, no
// game code), compiled by TeaVM into build/teavm-mesher/mesher.wasm with its loader beside it. The
// page's mesher workers load it (ADR 0023). Seconds to compile, not minutes.
val mesherOutput = layout.buildDirectory.dir("teavm-mesher")

val teavmMesher = tasks.register<JavaExec>("teavmMesher") {
    group = "build"
    description = "Compiles the mesher workers' program with TeaVM into build/teavm-mesher/."
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    maxHeapSize = "4g"
    val libraries = repoRoot.dir("work/cache/libraries").asFile
    val appClasspath = sourceSets.main.get().runtimeClasspath.filter { !it.startsWith(libraries) }
    inputs.files(appClasspath)
    val release = providers.gradleProperty("teavm.release").isPresent
    inputs.property("release", release)
    val output = mesherOutput
    outputs.dir(output)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--main", "incari.app.web.mesher.MesherWorker",
            "--classpath", appClasspath.files.joinToString(File.pathSeparator) { it.path },
            "--out", output.get().asFile.path,
            "--name", "mesher",
        ) + (if (release) listOf("--release") else emptyList())
    })
    systemProperty("incari.cwd", repoRoot.asFile.path)
}

// ---- The loader page, the asset pack and the build manifest ---------------------------------
// The page's TypeScript (src/main/ts) with the types stripped by Node, the asset pack (build-time data
// the page mounts read-only: the translated shaders, so it's Mojang content and never published), and
// build.json (every file's size and hash, which the loader and its service worker version files by).
// All land in build/page/; the dev server serves them beside build/teavm/. ADR 0014.
val pageDir = layout.buildDirectory.dir("page")

// Strips the types from each .ts file (Node's own stripping: the sources use only erasable syntax, as
// tsconfig.json enforces) and points relative imports at the .js files.
val stripTypesScript = """
    import { stripTypeScriptTypes } from "node:module";
    import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
    import { join } from "node:path";
    const [source, out] = process.argv.slice(-2);
    rmSync(out, { recursive: true, force: true });
    mkdirSync(out, { recursive: true });
    for (const name of readdirSync(source).filter((n) => n.endsWith(".ts"))) {
      const code = stripTypeScriptTypes(readFileSync(join(source, name), "utf8"), { mode: "strip" })
        .replace(/(from\s+["']\.\/[^"']+)\.ts(["'])/g, "${'$'}1.js${'$'}2");
      writeFileSync(join(out, name.replace(/\.ts${'$'}/, ".js")), code);
    }
""".trimIndent()

val pageScripts = tasks.register<Exec>("pageScripts") {
    group = "build"
    description = "Strips the types from the page's TypeScript into build/page/scripts/."
    val sources = layout.projectDirectory.dir("src/main/ts")
    val out = pageDir.get().dir("scripts").asFile
    inputs.dir(sources)
    inputs.property("script", stripTypesScript)
    outputs.dir(out)
    executable = "node"
    args("--no-warnings", "--input-type=module", "-e", stripTypesScript, sources.asFile.path, out.path)
}

val packManifest = layout.buildDirectory.file("pack/pack.json")
val writePackManifest = tasks.register("packManifest") {
    val version = minecraftVersion.get()
    val file = packManifest
    inputs.property("version", version)
    outputs.file(file)
    doLast { file.get().asFile.writeText("{\"format\": 1, \"game\": \"$version\"}\n") }
}

// Via's mapping data (ADR 0017), which it reads from the class path: the pack carries it
// under classpath/, which the page adds to the class path's roots.
val viaJars = configurations.runtimeClasspath.map { classpath ->
    classpath.filter { it.name.startsWith("viaversion-common-") || it.name.startsWith("viabackwards-common-") }
}

tasks.register<Zip>("assetPack") {
    group = "build"
    description = "Packs the build-time data the page mounts (translated shaders, decoded sprites, Via's data, Incari's pack) into build/page/incari.pack."
    dependsOn("shaders", "sprites", writePackManifest)
    from(packManifest)
    from(shaderManifest)
    from(spriteFile)
    from(viaJars.map { jars -> jars.map { zipTree(it) } }) {
        include("assets/viaversion/data/**", "assets/viabackwards/data/**")
        into("classpath")
    }
    // Incari's own resource pack (its font), which the client reads from the class path.
    from(rootProject.file("client/src/main/resources")) {
        include("incari-pack/**")
        into("classpath")
    }
    archiveFileName = "incari.pack"
    destinationDirectory = pageDir
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// The game's builds by the names the page fetches them by (tools/webbuild BuildManifest.BUILDS):
// Compatible's module as incari.wasm, Fast's as incari-fast.wasm, with their debugging files, and
// TeaVM's loader (one for both) from the build being compiled. Whichever are built are offered.
val gameModuleFiles: List<String> = run {
    val compatibleDir = layout.buildDirectory.dir("teavm").get().asFile
    val fastDir = layout.buildDirectory.dir("teavm-fast").get().asFile
    val suffixes = listOf("", "-deobfuscator.wasm", ".teadbg")
    listOf("incari.wasm-runtime.js=${layout.buildDirectory.dir(gameBuildDir).get().asFile.resolve("incari.wasm-runtime.js")}") +
        suffixes.map { "incari.wasm$it=${compatibleDir.resolve("incari.wasm$it")}" } +
        suffixes.map { "incari-fast.wasm$it=${fastDir.resolve("incari.wasm$it")}" }
}

// What the page loads, by the name it loads it by: the builds' files, the mesher, and the asset pack.
val buildFiles: List<String> = run {
    val mesherDir = layout.buildDirectory.dir("teavm-mesher").get().asFile
    gameModuleFiles +
        listOf("mesher.wasm", "mesher.wasm-runtime.js").map { "$it=${mesherDir.resolve(it)}" } +
        "incari.pack=${pageDir.get().file("incari.pack").asFile}"
}

tasks.register<JavaExec>("buildManifest") {
    group = "build"
    description = "Writes build/page/build.json: the build's files with their sizes and hashes."
    dependsOn("assetPack", teavmMesher)
    // After a compile in the same run, not beside it: hashing a module TeaVM is still writing gave
    // the page a manifest for the last build, and a cold start that never reached its first frame.
    mustRunAfter("teavm")
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.BuildManifest"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val out = pageDir.get().file("build.json").asFile
    val version = minecraftVersion.get()
    val files = buildFiles
    // Always: it hashes whatever build is there, which Gradle doesn't track between the tasks.
    outputs.upToDateWhen { false }
    outputs.file(out)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--out", out.path, "--game", version) + files.flatMap { listOf("--file", it) }
    })
}

// The loader page and its scripts, served by name over whatever the build's directory holds (the page
// there is a copy from the last compile), with both builds' modules by name. An experiment's build
// gets the page but no manifest, pack or other build.
fun pageServerArgs(experiment: Boolean): List<String> {
    val scripts = pageDir.get().dir("scripts").asFile
    val page = layout.projectDirectory.file("src/main/web/index.html").asFile
    val names = listOf("boot", "loader", "features", "jspi", "wakeups", "cache-policy", "sw", "game-worker")
    return listOf("--file", "index.html=$page") +
        names.flatMap { listOf("--file", "$it.js=${scripts.resolve("$it.js")}") } +
        (if (experiment) emptyList() else listOf(
            "--file", "mesher.wasm=${mesherOutput.get().file("mesher.wasm").asFile}",
            "--file", "mesher.wasm-runtime.js=${mesherOutput.get().file("mesher.wasm-runtime.js").asFile}",
            "--file", "build.json=${pageDir.get().file("build.json").asFile}",
            "--file", "incari.pack=${pageDir.get().file("incari.pack").asFile}") +
            gameModuleFiles.flatMap { listOf("--file", it) })
}

tasks.register<JavaExec>("serve") {
    group = "application"
    description = "Serves the game's builds on http://localhost:8080/ for development (never publish a build)."
    dependsOn("shaders", pageScripts, "buildManifest")
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.Serve"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val output = layout.buildDirectory.dir(gameBuildDir)
    val port = providers.gradleProperty("port").orElse("8080")
    // Resolved now: clientJar's mapping captures the build script, which the configuration cache can't store.
    val jar = clientJar.get()
    val manifest = shaderManifest
    val assets = assetServerArgs.get()
    val page = pageServerArgs(false)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(output.get().asFile.path, "--port", port.get(),
            "--file", "client.jar=$jar", "--file", "shaders.json=" + manifest.get().asFile.path) + assets + page
    })
}

// ---- The web folder --------------------------------------------------------------------------
// `./gradlew :app-web:webFolder -Pteavm.release` writes what `serve` serves into one folder any static
// web server can serve (tools/webbuild WebFolder, ADR 0028), with its own build.json, an .htaccess and
// a README.txt that says it must not be shared. `./incari build` runs it. Options: `-Pweb.out=<dir>`
// (default build/web/), `-Pweb.builds=fast,compatible` (the builds to put in, both by default; ADR
// 0036: each must be compiled, Fast with -Pteavm.fast, and both release builds), `-Pweb.noSounds`
// (leave the asset index out: ~460 MB of sounds, languages and the Unicode font), `-Pweb.skipBuild` (compile nothing first; otherwise the one
// build -Pteavm.fast picks is compiled). It holds Mojang's code and files: never published.
tasks.register<JavaExec>("webFolder") {
    group = "distribution"
    description = "Writes the browser builds as one folder for a static web server (never publish it)."
    if (!providers.gradleProperty("web.skipBuild").isPresent) {
        dependsOn("teavm")
    }
    dependsOn("assetPack", teavmMesher, pageScripts)
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.WebFolder"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val out = providers.gradleProperty("web.out").map { File(it).absolutePath }
        .orElse(layout.buildDirectory.dir("web").get().asFile.path)
    val builds = providers.gradleProperty("web.builds").orElse("fast,compatible").get()
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    builds.forEach { require(it == "fast" || it == "compatible") { "-Pweb.builds takes fast, compatible or both, not '$it'" } }
    val sounds = !providers.gradleProperty("web.noSounds").isPresent
    val soundFiles = when {
        providers.gradleProperty("web.noSoundFiles").isPresent -> "no"
        providers.gradleProperty("web.smallSounds").isPresent -> "small"
        providers.gradleProperty("web.coreSounds").isPresent -> "core"
        else -> "yes"
    }
    val version = minecraftVersion.get()
    val jar = clientJar.get()
    // The index and objects directory assetServerArgs serves, as WebFolder's two options.
    val assets = assetServerArgs.get().filterIndexed { i, _ -> i % 2 == 1 }
        .map { it.substringAfter('=') }
    val compatible = layout.buildDirectory.dir("teavm").get().asFile
    val fast = layout.buildDirectory.dir("teavm-fast").get().asFile
    val mesher = mesherOutput.get().asFile
    val page = layout.projectDirectory.file("src/main/web/index.html").asFile
    val scripts = pageDir.get().dir("scripts").asFile
    val pack = pageDir.get().file("incari.pack").asFile
    // Always: it copies whatever build is there, which Gradle doesn't track between the tasks.
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--out", out.get(),
            "--game", version,
            "--mesher", mesher.path,
            "--page", page.path,
            "--scripts", scripts.path,
            "--pack", pack.path,
            "--client-jar", jar,
        ) + (if ("fast" in builds) listOf("--fast", fast.path) else emptyList()) +
            (if ("compatible" in builds) listOf("--compatible", compatible.path) else emptyList()) +
            (if (sounds && assets.size == 2) listOf("--asset-index", assets[0], "--asset-objects", assets[1])
                else emptyList()) +
            listOf("--sound-files", soundFiles)
    })
}

// `./gradlew :app-web:webFolder :app-web:singleFile -Pteavm.release -Pweb.smallSounds -Pweb.html=<file>`
// packs the folder webFolder wrote (-Pweb.out) into one HTML file that plays from the disk (tools/webbuild
// SingleFile, ADR 0057); `./incari build --html` runs it. -Pweb.smallSounds keeps the languages, the
// Unicode font and every sound but the music, encoded smaller with ffmpeg (~38 MB of 377;
// tools/webbuild SmallSounds); -Pweb.coreSounds ~10 MB of common sounds as they are (CoreSounds);
// -Pweb.noSoundFiles none of the sounds. Like the folder, never published.
tasks.register<JavaExec>("singleFile") {
    group = "distribution"
    description = "Packs the web folder into one HTML file that plays from the disk (never publish it)."
    dependsOn(pageScripts)
    mustRunAfter("webFolder")
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.SingleFile"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val folder = providers.gradleProperty("web.out").map { File(it).absolutePath }
        .orElse(layout.buildDirectory.dir("web").get().asFile.path)
    val html = providers.gradleProperty("web.html").map { File(it).absolutePath }
        .orElse(layout.buildDirectory.file("incari.html").get().asFile.path)
    val script = pageDir.get().dir("scripts").file("offline.js").asFile
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--folder", folder.get(), "--out", html.get(), "--script", script.path)
    })
}

tasks.register<JavaExec>("browserCheck") {
    group = "verification"
    description = "Runs build/teavm/ in headless Chromium until the game loop's heartbeat shows."
    // `-Pbrowser.html=<file>`: a single-file build (singleFile, ADR 0057) instead, opened from the
    // disk with nothing compiled or served; the other options work as for the build.
    val html = providers.gradleProperty("browser.html").map { File(it).absolutePath }
    if (!html.isPresent) {
        dependsOn("teavm", "shaders", pageScripts)
    }
    val experiment = providers.gradleProperty("teavm.main").isPresent
    if (!experiment && !html.isPresent) {
        dependsOn("buildManifest")
    }
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.BrowserCheck"
    // The build the page runs (PageQuery); none for an experiment's, which has one module.
    systemProperty("incari.build", if (providers.gradleProperty("teavm.main").isPresent) "" else gameBuild)
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val output = layout.buildDirectory.dir(
        providers.gradleProperty("teavm.main").map { "teavm-experiment" }.orElse(gameBuildDir))
    // `-Pbrowser.expect=<regex> -Pbrowser.count=<n>` for experiments (see the teavm task).
    val expect = providers.gradleProperty("browser.expect")
    val count = providers.gradleProperty("browser.count")
    val seconds = providers.gradleProperty("browser.seconds")
    // `-Pbrowser.query=renderer=headless` and the like: page options (see WebMain).
    val query = providers.gradleProperty("browser.query")
    // `-Pbrowser.steps="shot:build/title.png;click:0.5,0.45;sleep:2000;shot:build/next.png"`: what to do
    // once the game runs, through the DevTools protocol (BrowserCheck.runStep lists the steps). A `;`
    // starts a new step only before a step's name, so `eval:` JavaScript may contain them.
    // `-Pbrowser.steps=@browser-checks/text-input.steps` reads them from a file, a step per line.
    val steps = providers.gradleProperty("browser.steps")
    val projectDir = layout.projectDirectory
    val stepNames ="sleep|wait|shot|move|click|drag|hold|type|eval|init|key|down|up|assert|waitfor|holds|until|resize|clipboard|compose|insert|drop|rcon|rconwait|reload|choose|downloads|download|pack|profile"
    // `-Pbrowser.rcon=127.0.0.1:25592`: the game server's remote console, for `rcon:` steps;
    // the password comes from INCARI_RCON_PASSWORD.
    val rcon = providers.gradleProperty("browser.rcon")
    // `-Pbrowser.gpu=gl` (or vulkan): WebGL on this machine's GPU instead of SwiftShader, for
    // timing frames; `webgpu`: Vulkan with WebGPU on the GPU too (probes/WebGpuCallProbe).
    val gpu = providers.gradleProperty("browser.gpu")
    val jar = clientJar.get()
    val manifest = shaderManifest
    val assets = assetServerArgs.get()
    val page = pageServerArgs(experiment)
    argumentProviders.add(CommandLineArgumentProvider {
        (if (html.isPresent) listOf(html.get()) else listOf(output.get().asFile.path,
            "--file", "client.jar=$jar", "--file", "shaders.json=" + manifest.get().asFile.path) + assets + page) +
            (if (query.isPresent) listOf("--query", query.get()) else emptyList()) +
            (if (!steps.isPresent) emptyList()
                else if (steps.get().startsWith("@")) listOf("--steps-file", projectDir.file(steps.get().substring(1)).asFile.path)
                else steps.get().split(Regex(";(?=($stepNames):)")).filter { it.isNotBlank() }.flatMap { listOf("--step", it) }) +
            (if (seconds.isPresent) listOf("--seconds", seconds.get()) else emptyList()) +
            (if (rcon.isPresent) listOf("--rcon", rcon.get()) else emptyList()) +
            (if (gpu.isPresent) listOf("--gpu", gpu.get()) else emptyList()) +
            (if (expect.isPresent) listOf("--expect", expect.get()) else emptyList()) +
            (if (count.isPresent) listOf("--count", count.get()) else emptyList())
    })
}

// ---- Screenshot diffs against the desktop ----------------------------------------------------
// `./gradlew :app-web:screenshotDiff` renders the scenes in screenshot-scenes/scenes.txt with the
// desktop game (Mojang's OpenGL backend) and with the browser build (WebGL2, in headless Chromium
// on this machine's GPU), both joined to a fresh vanilla server, and fails if a pair is less alike
// (SSIM) than the file's threshold, or if the page logs a WebGL error. The images are renders of
// Mojang's assets: they go to build/screenshots/ and are never committed. Needs a display and a GPU
// (the desktop game opens a window). Options: `-Pscreenshots.only=terrain,sky`,
// `-Pscreenshots.gpu=gl|vulkan|swiftshader` (default gl: ANGLE on the desktop's own driver),
// `-Pscreenshots.reuseDesktop` (keep the desktop images already rendered), `-Pscreenshots.skipBuild`
// (use build/teavm/ as it is instead of compiling first), `-Pscreenshots.port=18210` (six ports up
// from it: server, RCON, bridge, desktop agent, page, DevTools).
tasks.register<JavaExec>("screenshotDiff") {
    group = "verification"
    description = "Renders fixed scenes on the desktop (OpenGL) and in Chromium (WebGL2) and compares them."
    if (!providers.gradleProperty("screenshots.skipBuild").isPresent) {
        dependsOn("teavm")
    }
    dependsOn("shaders", ":app-desktop:sceneLaunch", pageScripts, "buildManifest")
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.ScreenshotDiff"
    // The build the page runs (PageQuery, ADR 0036).
    systemProperty("incari.build", gameBuild)
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val scenes = layout.projectDirectory.dir("screenshot-scenes")
    val output = layout.buildDirectory.dir(gameBuildDir)
    val out = layout.buildDirectory.dir("screenshots")
    val launch = project(":app-desktop").layout.buildDirectory.file("scene-launch.txt")
    val versionDir = minecraftVersion.map { repoRoot.dir("work/cache/versions/$it").asFile.path }.get()
    val version = minecraftVersion.get()
    val jar = clientJar.get()
    val manifest = shaderManifest
    val only = providers.gradleProperty("screenshots.only")
    // `-Pscreenshots.query=sprites=off`: extra page options for the browser's run, to compare.
    val screenshotQuery = providers.gradleProperty("screenshots.query")
    val gpu = providers.gradleProperty("screenshots.gpu").orElse("gl")
    val port = providers.gradleProperty("screenshots.port").orElse("18210")
    val reuseDesktop = providers.gradleProperty("screenshots.reuseDesktop").isPresent
    val page = pageServerArgs(false)
    // Always runs: its inputs include a GPU, a server and two games.
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--scenes", scenes.file("scenes.txt").asFile.path,
            "--options", scenes.file("options.txt").asFile.path,
            "--server-properties", scenes.file("server.properties").asFile.path,
            "--out", out.get().asFile.path,
            "--build", output.get().asFile.path,
            "--file", "client.jar=$jar",
            "--file", "shaders.json=" + manifest.get().asFile.path,
            "--desktop-launch", launch.get().asFile.path,
            "--version-json", "$versionDir/$version.json",
            "--cache", versionDir,
            "--gpu", gpu.get(),
            "--port", port.get(),
        ) + page + (if (only.isPresent) listOf("--only", only.get()) else emptyList()) +
            (if (screenshotQuery.isPresent) listOf("--query", screenshotQuery.get()) else emptyList()) +
            (if (reuseDesktop) listOf("--reuse-desktop") else emptyList())
    })
}

// ---- The benchmark ---------------------------------------------------------------------------
// `./gradlew :app-web:benchmark` plays benchmark/benchmark.txt's scenarios (a fly-through over fresh
// terrain, a fight in an arena) in the browser build against a fresh vanilla world, times the loads
// (cold and warm start to the title screen, the join), and reports what the page's profiler
// measured: frame rate and 1% low, tick, extract, render submit and GPU time, draw calls, the mesh
// queue, memory and GC pauses. The report goes to build/benchmark/ (report.md, report.json) and, in
// CI, to the job summary. It fails if a figure regressed past its rule against this machine's
// baseline (benchmark/baseline.properties). Needs a GPU for meaningful figures. Options:
// `-Pbenchmark.machine=<name>` (default: the host name), `-Pbenchmark.updateBaseline` (record this
// machine's figures instead of comparing), `-Pbenchmark.only=flythrough`, `-Pbenchmark.query=multidraw=off`
// (extra page options), `-Pbenchmark.gpu=gl|vulkan|swiftshader` (default gl), `-Pbenchmark.headed` (a
// Chromium window instead of headless), `-Pbenchmark.connect=<host>:<port> -Pbenchmark.pageHost=<address>`
// (a Chrome reachable over DevTools, a Chromebook's: docs/benchmark.md), `-Pbenchmark.skipBuild`
// (use build/teavm/ as it is), `-Pbenchmark.port=18310` (five ports up from it: server, RCON, bridge,
// page, DevTools), `-Pbenchmark.history=<file>` (default ~/.incari/benchmark-history.jsonl: the runs the
// report's trend charts come from, kept on this machine only), `-Pbenchmark.cpuThrottle=<rate>` (the
// page's CPU that many times slower: a Chromebook stand-in, under its own machine name),
// `-Pbenchmark.singleplayer` (benchmark/singleplayer.txt in a world the page creates and runs itself,
// ADR 0039: no server here; its own baseline, singleplayer-baseline.properties, and history). Run
// :app-web:checkDownloadSize first
// to have the download size in the report.
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Plays the benchmark's scenarios in the browser build and holds the figures to the baseline."
    if (!providers.gradleProperty("benchmark.skipBuild").isPresent) {
        dependsOn("teavm")
    }
    dependsOn("shaders", pageScripts, "buildManifest")
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.Benchmark"
    // The build the page runs (PageQuery, ADR 0036).
    systemProperty("incari.build", gameBuild)
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val dir = layout.projectDirectory.dir("benchmark")
    val output = layout.buildDirectory.dir(gameBuildDir)
    val out = layout.buildDirectory.dir("benchmark")
    val downloadReport = layout.buildDirectory.file("reports/download-size.json")
    val versionDir = minecraftVersion.map { repoRoot.dir("work/cache/versions/$it").asFile.path }.get()
    val version = minecraftVersion.get()
    val jar = clientJar.get()
    val manifest = shaderManifest
    val assets = assetServerArgs.get()
    val page = pageServerArgs(false)
    val machine = providers.gradleProperty("benchmark.machine")
    val update = providers.gradleProperty("benchmark.updateBaseline").isPresent
    val only = providers.gradleProperty("benchmark.only")
    val query = providers.gradleProperty("benchmark.query")
    val gpu = providers.gradleProperty("benchmark.gpu").orElse("gl")
    val headed = providers.gradleProperty("benchmark.headed").isPresent
    // `-Pbenchmark.cpuThrottle=4`: Chrome runs the page's CPU four times slower, a stand-in for a
    // Chromebook's CPU (ADR 0027); record it under a machine name of its own.
    val cpuThrottle = providers.gradleProperty("benchmark.cpuThrottle")
    // `-Pbenchmark.gcTrace`: each scenario's collector events, phases included, in time order
    // (build/benchmark/<scenario>-<n>.gc.txt; ADR 0027).
    val gcTrace = providers.gradleProperty("benchmark.gcTrace").isPresent
    // `-Pbenchmark.profile`: a CPU profile of each measured stretch, build/benchmark/<scenario>-<n>.cpuprofile.
    val profile = providers.gradleProperty("benchmark.profile").isPresent
    // `-Pbenchmark.heapProfile`: the heap sampler's live objects at the title screen and after each
    // scenario, build/benchmark/<name>.heapprofile.
    val heapProfile = providers.gradleProperty("benchmark.heapProfile").isPresent
    // `-Pbenchmark.heapSnapshot`: heap snapshots at the same points, with their sizes by type.
    val heapSnapshot = providers.gradleProperty("benchmark.heapSnapshot").isPresent
    // `-Pbenchmark.desktopJfr`: the desktop game plays the scenarios instead of the page, each
    // measured stretch recorded by the Flight Recorder with its allocations by site.
    val desktopJfr = providers.gradleProperty("benchmark.desktopJfr").isPresent
    if (desktopJfr) {
        dependsOn(":app-desktop:sceneLaunch")
    }
    val desktopLaunch = project(":app-desktop").layout.buildDirectory.file("scene-launch.txt")
    val connect = providers.gradleProperty("benchmark.connect")
    val pageHost = providers.gradleProperty("benchmark.pageHost")
    val port = providers.gradleProperty("benchmark.port").orElse("18310")
    // `-Pbenchmark.singleplayer`: singleplayer's scenarios, figures and baseline instead (ADR 0039).
    val singleplayer = providers.gradleProperty("benchmark.singleplayer").isPresent
    // `-Pbenchmark.script=hudeditor.txt`: another script in benchmark/ (with -Pbenchmark.singleplayer
    // for one that plays in the page's own world), measured but kept out of the usual history.
    val script = providers.gradleProperty("benchmark.script")
    val history = providers.gradleProperty("benchmark.history")
        .orElse(
            System.getProperty("user.home") + "/.incari/benchmark-" +
                (if (singleplayer) "singleplayer-" else "") + "history.jsonl",
        )
    // Always runs: its inputs include a GPU, a server and a browser.
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--script",
            dir.file(script.getOrElse(if (singleplayer) "singleplayer.txt" else "benchmark.txt")).asFile.path,
            "--options", dir.file("options.txt").asFile.path,
            "--server-properties", dir.file("server.properties").asFile.path,
            "--baseline",
            dir.file(if (singleplayer) "singleplayer-baseline.properties" else "baseline.properties").asFile.path,
            "--budgets", dir.file("budgets.properties").asFile.path,
            "--out", out.get().asFile.path,
            "--build", output.get().asFile.path,
            "--file", "client.jar=$jar",
            "--file", "shaders.json=" + manifest.get().asFile.path,
            "--version-json", "$versionDir/$version.json",
            "--cache", versionDir,
            "--download-report", downloadReport.get().asFile.path,
            "--history", history.get(),
            "--gpu", gpu.get(),
            "--port", port.get(),
        ) + assets + page +
            (if (machine.isPresent) listOf("--machine", machine.get()) else emptyList()) +
            (if (update) listOf("--update-baseline") else emptyList()) +
            (if (only.isPresent) listOf("--only", only.get()) else emptyList()) +
            (if (query.isPresent) listOf("--query", query.get()) else emptyList()) +
            (if (headed) listOf("--headed") else emptyList()) +
            (if (profile) listOf("--profile") else emptyList()) +
            (if (heapProfile) listOf("--heap-profile") else emptyList()) +
            (if (heapSnapshot) listOf("--heap-snapshot") else emptyList()) +
            (if (desktopJfr) listOf("--desktop-launch", desktopLaunch.get().asFile.path) else emptyList()) +
            (if (connect.isPresent) listOf("--connect", connect.get()) else emptyList()) +
            (if (pageHost.isPresent) listOf("--page-host", pageHost.get()) else emptyList()) +
            (if (cpuThrottle.isPresent) listOf("--cpu-throttle", cpuThrottle.get()) else emptyList()) +
            (if (gcTrace) listOf("--gc-trace") else emptyList()) +
            (if (singleplayer) listOf("--singleplayer") else emptyList())
    })
}

// ---- Download-size budget --------------------------------------------------------------------
// `./gradlew :app-web:checkDownloadSize` measures what a player downloads (the build output and
// the files the dev server serves beside it) against download-budget.properties, raw, gzip and
// brotli, and fails when a file is over budget or unclassified. It writes
// build/reports/download-size.json for charting. `-PdownloadSize.skipBuild` measures the build
// already in build/teavm/ instead of compiling first (the compile takes about ten minutes);
// `-PdownloadSize.noBrotli` skips brotli, whose highest quality takes a minute or two.
tasks.register<JavaExec>("checkDownloadSize") {
    group = "verification"
    description = "Checks the browser build's download size against download-budget.properties."
    val skipBuild = providers.gradleProperty("downloadSize.skipBuild").isPresent
    if (!skipBuild) {
        dependsOn("teavm")
        // The build-time shader manifest, once that task exists.
        dependsOn(tasks.matching { it.name == "shaders" })
    }
    dependsOn(pageScripts, "buildManifest")
    classpath = webbuildTool
    mainClass = "incari.tools.webbuild.DownloadSize"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val budget = layout.projectDirectory.file("download-budget.properties")
    val output = layout.buildDirectory.dir(gameBuildDir)
    val shaders = layout.buildDirectory.file("web-shaders/shaders.json")
    val report = layout.buildDirectory.file("reports/download-size.json")
    val noBrotli = providers.gradleProperty("downloadSize.noBrotli").isPresent
    // Resolved now: clientJar's mapping captures the build script, which the configuration cache
    // can't store inside a task.
    val vendorJar = clientJar.get()
    // The loader page: its scripts, the build manifest and the asset pack.
    val scripts = pageDir.get().dir("scripts").asFile
    val pageFiles = listOf("boot", "loader", "features", "jspi", "wakeups", "cache-policy", "sw", "game-worker").map { "$it.js=${scripts.resolve("$it.js")}" } +
        listOf("build.json=${pageDir.get().file("build.json").asFile}", "incari.pack=${pageDir.get().file("incari.pack").asFile}")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--budget", budget.asFile.path,
            "--dir", output.get().asFile.path,
            // Files the page fetches from outside build/teavm/: <name as the page fetches it>=<path>.
            "--file", "shaders.json=" + shaders.get().asFile.path,
            "--file", "client.jar=" + vendorJar,
            "--report", report.get().asFile.path,
        ) + pageFiles.flatMap { listOf("--file", it) } + (if (noBrotli) listOf("--no-brotli") else emptyList())
    })
}
