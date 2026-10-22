package incari.tools.webbuild;

import incari.tools.build.BuildConfig;
import incari.tools.build.CliException;
import incari.tools.build.GameFiles;
import incari.tools.build.Log;
import incari.tools.build.Workspace;
import incari.tools.survey.HostJdkHidingLoader;
import incari.tools.survey.Origins;
import incari.tools.survey.SurveyRules;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemSeverity;
import org.teavm.tooling.TeaVMProblemRenderer;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Compiles the browser build with TeaVM: the game as {@code :client} built it, the web
 * platform, and the Mojang libraries {@code tools/survey/rules.txt} keeps, from one entry point.
 * Output (never committed or published) goes to {@code app-web/build/teavm}.
 *
 * <p>Run by {@code ./gradlew :app-web:teavm}, which passes the Incari class directories.
 */
public final class Main {

    /** The game targets Java 25, and TeaVM compiles missing JDK classes from the running JDK. */
    static final int REQUIRED_JDK = 25;

    private Main() {}

    /** TeaVM's optimizer and code generator recurse deeply on the game's largest methods. */
    private static final long COMPILER_STACK_BYTES = 1L << 30;

    public static void main(String[] args) throws InterruptedException {
        int[] status = {2};
        Thread compiler = new Thread(
                null,
                () -> {
                    try {
                        status[0] = run(Options.parse(List.of(args)));
                    } catch (CliException e) {
                        System.err.println("error: " + e.getMessage());
                        status[0] = 1;
                    } catch (Exception | StackOverflowError e) {
                        e.printStackTrace();
                        status[0] = 2;
                    }
                },
                "teavm",
                COMPILER_STACK_BYTES);
        compiler.start();
        compiler.join();
        System.exit(status[0]);
    }

    static int run(Options options) throws Exception {
        int jdk = Runtime.version().feature();
        if (jdk != REQUIRED_JDK) {
            throw new CliException("The web build must run on JDK " + REQUIRED_JDK + " (this is JDK " + jdk + ")");
        }
        checkTeaVmPatches();
        Workspace ws = Workspace.discover(Path.of(System.getProperty("incari.cwd", ".")));
        BuildConfig config = BuildConfig.load(ws.repoRoot());
        GameFiles game = GameFiles.load(ws, config);
        SurveyRules rules = SurveyRules.load(ws.repoRoot().resolve("tools/survey/rules.txt"));

        // The game's classes come from :client's output, not Mojang's jar; the jar map only tells
        // Origins which classes are the game's, so the JDK hiding never hides one of them.
        Map<Path, String> jars = new LinkedHashMap<>();
        jars.put(game.clientJar(), Origins.MINECRAFT);
        List<Path> classpath = new ArrayList<>(options.appClasspath());
        for (GameFiles.LibraryJar library : game.libraries()) {
            String name = library.library().name();
            if (rules.library(name).action() == SurveyRules.LibraryAction.KEEP) {
                jars.put(library.jar(), name.split(":", -1)[1]);
                classpath.add(library.jar());
            }
        }
        Origins origins = new Origins(jars, Main.class.getClassLoader());

        Files.createDirectories(options.outputDirectory());
        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = classpath.get(i).toUri().toURL();
        }
        Log.step("Compiling " + options.mainClass() + " with TeaVM (" + options.target() + ")");
        ClassLoader parent = new HostJdkHidingLoader(Main.class.getClassLoader(), origins);
        try (URLClassLoader loader = new URLClassLoader(urls, parent)) {
            TeaVMTool tool = new TeaVMTool();
            tool.setTargetType(options.target());
            tool.setMainClass(options.mainClass());
            tool.setEntryPointName("main");
            tool.setClassLoader(loader);
            tool.setTargetDirectory(options.outputDirectory().toFile());
            // TeaVM adds no extension to the JavaScript build's file; the page loads incari.js.
            tool.setTargetFileName(
                    options.target() == TeaVMTargetType.JAVASCRIPT ? options.name() + ".js" : options.name());
            tool.setOptimizationLevel(
                    options.release() ? TeaVMOptimizationLevel.ADVANCED : TeaVMOptimizationLevel.SIMPLE);
            // `--names` keeps a release build's Java names (the module's name section), so CPU
            // profiles of it read as the game's methods; the code is the release build's.
            tool.setObfuscated(options.release() && !options.names());
            tool.setIncremental(false);
            // Null checks that throw NullPointerException instead of trapping, in every build: the
            // game catches it (its system report at start, the section dispatcher's runTask), and a
            // release build without them crashed at start with "dereferencing a null pointer".
            tool.setStrict(true);
            if (!options.release()) {
                // Development builds: Java names in stack traces, with the deobfuscator the page loads.
                tool.setDebugInformationGenerated(true);
            }
            tool.setFastDependencyAnalysis(options.fastAnalysis());
            tool.setLog(new ConsoleLog(options.verbose()));
            Removals removals = new Removals(rules, origins);
            RemovalTransformer.removals = removals;
            MonitorElision.classes = classlibClasses(options.appClasspath());
            TypeCaptures.captures = TypeCaptures.scan(classpath);
            Log.step("Keeping the generic superclass of " + TypeCaptures.captures.size() + " type-capture classes");
            tool.getTransformers().add(ClasslibRenamer.class.getName());
            tool.getTransformers().add(MonitorElision.class.getName());
            tool.getTransformers().add(RemovalTransformer.class.getName());
            tool.getTransformers().add(AdditionsTransformer.class.getName());
            tool.getTransformers().add(TypeCaptures.class.getName());
            // `-Pteavm.throwCensus` (development): every throw counted, by site and thread (ADR 0033).
            boolean throwCensus = Boolean.getBoolean("incari.throwCensus");
            if (throwCensus) {
                tool.getTransformers().add(ThrowCensus.class.getName());
            }
            // `--jspi`: suspensions through JSPI instead of coroutines (JspiTransformer).
            if (options.jspi()) {
                tool.getTransformers().add(JspiTransformer.class.getName());
                System.setProperty(JspiTransformer.PROPERTY, "true");
            }
            try {
                tool.generate();
            } finally {
                RemovalTransformer.removals = null;
                TypeCaptures.captures = Map.of();
                System.clearProperty(JspiTransformer.PROPERTY);
            }
            removals.write(options.outputDirectory().resolve("removed.txt"));
            if (throwCensus) {
                ThrowCensus.write(options.outputDirectory().resolve("throw-sites.txt"));
            }

            List<Problem> errors = tool.getProblemProvider().getSevereProblems();
            writeAsyncExplanations(tool, errors, options.outputDirectory().resolve("async.txt"), options.jspi());
            writeMissingSignatureTypes(tool, options.outputDirectory().resolve("signatures.txt"));
            BlockingCalls.write(
                    tool.getDependencyInfo(), options.outputDirectory().resolve("blocking.txt"));
            ProblemReport report = ProblemReport.of(tool.getProblemProvider().getProblems());
            report.write(options.outputDirectory().resolve("problems.txt"));
            if (!errors.isEmpty()) {
                System.err.println(report.summary(40));
                if (options.verbose()) {
                    TeaVMProblemRenderer.describeProblems(
                            tool.getDependencyInfo().getCallGraph(), tool.getProblemProvider(), tool.getLog());
                }
                throw new CliException(errors.size() + " link errors; all of them are in "
                        + options.outputDirectory().resolve("problems.txt") + " (" + removals.count()
                        + " references removed, in removed.txt)");
            }
            long warnings = tool.getProblemProvider().getProblems().stream()
                    .filter(p -> p.getSeverity() == ProblemSeverity.WARNING)
                    .count();
            if (options.target() == TeaVMTargetType.WEBASSEMBLY_GC) {
                writeRuntime(options.outputDirectory(), options.name(), options.release());
                if (options.release()) {
                    // A development build's debugging files, left from an earlier compile: the
                    // build manifest takes their presence for a development build.
                    Files.deleteIfExists(options.outputDirectory().resolve(options.name() + ".wasm-deobfuscator.wasm"));
                    Files.deleteIfExists(options.outputDirectory().resolve(options.name() + ".wasm.teadbg"));
                    // Binaryen over the module (ADR 0034).
                    if (options.wasmOpt()) {
                        WasmOpt.optimize(ws, options.outputDirectory().resolve(options.name() + ".wasm"));
                    }
                } else {
                    writeResource(
                            "org/teavm/backend/wasm/deobfuscator.wasm",
                            options.outputDirectory().resolve(options.name() + ".wasm-deobfuscator.wasm"));
                }
            }
            if (options.page() != null) {
                Files.copy(
                        options.page(),
                        options.outputDirectory().resolve("index.html"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Log.step("Linked: " + tool.getDependencyInfo().getReachableClasses().size() + " classes, " + warnings
                    + " warnings, " + removals.count() + " references removed (removed.txt), output in "
                    + options.outputDirectory());
            return 0;
        }
    }

    /**
     * The class library's classes as the compile sees them: substitutes under their JDK names,
     * everything else under its own.
     */
    private static Set<String> classlibClasses(List<Path> classpath) throws IOException {
        Set<String> names = new HashSet<>();
        for (Path entry : classpath) {
            if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(entry.toFile())) {
                    jar.stream().map(java.util.jar.JarEntry::getName).forEach(name -> addClasslibClass(names, name));
                }
            } else if (Files.isDirectory(entry)) {
                try (java.util.stream.Stream<Path> files = Files.walk(entry)) {
                    files.forEach(file -> addClasslibClass(
                            names, entry.relativize(file).toString().replace(java.io.File.separatorChar, '/')));
                }
            }
        }
        return Set.copyOf(names);
    }

    private static void addClasslibClass(Set<String> names, String path) {
        if (path.startsWith("incari/classlib/") && path.endsWith(".class")) {
            String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
            names.add(ClasslibRenamer.original(name));
        }
    }

    /**
     * For each method TeaVM failed to generate (its coroutine transformation has gaps), writes why
     * the method is a coroutine at all: usually a {@code synchronized} override somewhere deep in
     * a virtual call, which is what to remove.
     */
    private static void writeAsyncExplanations(TeaVMTool tool, List<Problem> errors, Path file, boolean jspi)
            throws IOException {
        List<String> lines = new ArrayList<>();
        if (jspi) {
            lines.add("# A JSPI build: nothing below is compiled as a coroutine. The figures are what a");
            lines.add("# coroutine build of the same program would compile, for comparison.");
        }
        AsyncExplainer explainer = new AsyncExplainer(tool.getDependencyInfo());
        for (Problem problem : errors) {
            if (problem.getLocation() == null
                    || problem.getLocation().getMethod() == null
                    || !problem.getText().startsWith("Failed generating method body")) {
                continue;
            }
            lines.add(problem.getLocation().getMethod().toString());
            for (org.teavm.model.MethodReference step :
                    explainer.explain(problem.getLocation().getMethod())) {
                lines.add("    -> " + step);
            }
        }
        // -Dincari.explainAsync=<method name prefix>,... explains chosen methods too.
        for (String wanted : System.getProperty("incari.explainAsync", "").split(",", -1)) {
            if (wanted.isBlank()) {
                continue;
            }
            for (org.teavm.model.MethodReference method :
                    tool.getDependencyInfo().getReachableMethods()) {
                if (method.toString().startsWith(wanted)) {
                    lines.add("EXPLAIN " + method);
                    for (org.teavm.model.MethodReference step : explainer.explain(method)) {
                        lines.add("    -> " + step);
                    }
                }
            }
        }
        lines.add("");
        lines.add("# Hot virtual methods compiled as suspension points (should be none):");
        lines.addAll(explainer.hotReport());
        Files.write(file, lines);
    }

    /**
     * Fails unless every class in tools/teavm-patches, not TeaVM's original of it, is the one
     * loaded. The patches' jar must come before TeaVM's on the class path.
     */
    private static void checkTeaVmPatches() throws IOException {
        Path jar = null;
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator, -1)) {
            if (Path.of(entry).getFileName().toString().startsWith("teavm-patches")) {
                jar = Path.of(entry);
            }
        }
        if (jar == null) {
            throw new CliException("tools/teavm-patches isn't on the build tool's class path");
        }
        List<String> names = new ArrayList<>();
        try (java.util.jar.JarFile patches = new java.util.jar.JarFile(jar.toFile())) {
            patches.stream()
                    .map(java.util.jar.JarEntry::getName)
                    .filter(name -> name.endsWith(".class"))
                    .forEach(name -> names.add(
                            name.substring(0, name.length() - ".class".length()).replace('/', '.')));
        }
        for (String name : names) {
            Class<?> loaded;
            try {
                loaded = Class.forName(name, false, Main.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new CliException("Can't load " + name + " from tools/teavm-patches: " + e.getMessage());
            }
            java.security.CodeSource source = loaded.getProtectionDomain().getCodeSource();
            if (source == null || !source.getLocation().toString().contains("teavm-patches")) {
                throw new CliException("TeaVM's own " + name + " was loaded (from "
                        + (source == null ? "unknown" : source.getLocation()) + ") instead of the fix in"
                        + " tools/teavm-patches; it must come first on the class path");
            }
        }
    }

    /**
     * Reachable methods whose signatures name a class that doesn't exist. Removal rewrites the
     * instructions that use a missing class, not signatures, and TeaVM types a missing class in a
     * signature inconsistently, which fails WebAssembly validation (a bridge method's call into
     * its target, say). Each needs its class provided ({@code web-classlib}) or its code cut.
     */
    private static void writeMissingSignatureTypes(TeaVMTool tool, Path file) throws IOException {
        org.teavm.model.ClassReaderSource classes = tool.getDependencyInfo().getClassSource();
        java.util.TreeMap<String, java.util.TreeSet<String>> byType = new java.util.TreeMap<>();
        for (org.teavm.model.MethodReference method : tool.getDependencyInfo().getReachableMethods()) {
            for (org.teavm.model.ValueType type : method.getSignature()) {
                org.teavm.model.ValueType item = type;
                while (item instanceof org.teavm.model.ValueType.Array array) {
                    item = array.getItemType();
                }
                if (item instanceof org.teavm.model.ValueType.Object object
                        && classes.get(object.getClassName()) == null) {
                    byType.computeIfAbsent(object.getClassName(), k -> new java.util.TreeSet<>())
                            .add(method.toString());
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Reachable methods whose signatures name a missing class (should be none):");
        byType.forEach((type, methods) -> {
            lines.add(type + " (" + methods.size() + ")");
            methods.stream().limit(5).forEach(m -> lines.add("    " + m));
        });
        Files.write(file, lines);
    }

    /**
     * Writes TeaVM's loader for WebAssembly GC modules next to the module, as
     * {@code <name>.wasm-runtime.js}; the page (or a worker) loads it, then the module through it.
     */
    private static void writeRuntime(Path outputDirectory, String name, boolean minified) throws IOException {
        String resource = "org/teavm/backend/wasm/wasm-gc-runtime" + (minified ? ".min" : "") + ".js";
        try (InputStream runtime = Main.class.getClassLoader().getResourceAsStream(resource)) {
            if (runtime == null) {
                throw new CliException("TeaVM's WebAssembly GC runtime (" + resource + ") isn't on the classpath");
            }
            Files.copy(
                    runtime, outputDirectory.resolve(name + ".wasm-runtime.js"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeResource(String resource, Path target) throws IOException {
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new CliException(resource + " isn't on the classpath");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Command-line options; Gradle passes them. */
    record Options(
            String mainClass,
            List<Path> appClasspath,
            Path outputDirectory,
            TeaVMTargetType target,
            @Nullable Path page,
            String name,
            boolean release,
            boolean names,
            boolean wasmOpt,
            boolean jspi,
            boolean fastAnalysis,
            boolean verbose) {

        static Options parse(List<String> args) {
            String mainClass = null;
            List<Path> appClasspath = new ArrayList<>();
            Path output = null;
            Path page = null;
            String name = "incari";
            TeaVMTargetType target = TeaVMTargetType.WEBASSEMBLY_GC;
            boolean release = false;
            boolean names = false;
            boolean wasmOpt = false;
            boolean jspi = false;
            boolean fast = false;
            boolean verbose = false;
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                switch (arg) {
                    case "--main" -> mainClass = value(args, ++i, arg);
                    case "--classpath" -> {
                        for (String entry : value(args, ++i, arg).split(java.io.File.pathSeparator, -1)) {
                            if (!entry.isEmpty()) {
                                appClasspath.add(Path.of(entry));
                            }
                        }
                    }
                    case "--out" -> output = Path.of(value(args, ++i, arg));
                    case "--page" -> page = Path.of(value(args, ++i, arg));
                    case "--name" -> name = value(args, ++i, arg);
                    case "--js" -> target = TeaVMTargetType.JAVASCRIPT;
                    case "--release" -> release = true;
                    case "--names" -> names = true;
                    case "--wasm-opt" -> wasmOpt = true;
                    case "--jspi" -> jspi = true;
                    case "--fast-analysis" -> fast = true;
                    case "--verbose" -> verbose = true;
                    default -> throw new CliException("Unknown option '" + arg + "'");
                }
            }
            if (mainClass == null || output == null) {
                throw new CliException("Usage: webbuild --main <class> --classpath <entries> --out <dir>"
                        + " [--page <html>] [--name <file name>] [--js] [--release] [--names] [--wasm-opt]"
                        + " [--jspi] [--fast-analysis] [--verbose]");
            }
            if (wasmOpt && (!release || target != TeaVMTargetType.WEBASSEMBLY_GC)) {
                throw new CliException("--wasm-opt optimizes a release WebAssembly build: add --release, without --js");
            }
            if (jspi && target != TeaVMTargetType.WEBASSEMBLY_GC) {
                throw new CliException("--jspi is for the WebAssembly build: leave out --js");
            }
            return new Options(
                    mainClass,
                    List.copyOf(appClasspath),
                    output,
                    target,
                    page,
                    name,
                    release,
                    names,
                    wasmOpt,
                    jspi,
                    fast,
                    verbose);
        }

        private static String value(List<String> args, int index, String option) {
            if (index >= args.size()) {
                throw new CliException(option + " needs a value");
            }
            return args.get(index);
        }
    }
}
