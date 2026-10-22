import com.diffplug.gradle.spotless.SpotlessExtension
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

// Shared conventions for every Incari JVM module. Module-specific settings live in each
// module's own build.gradle.kts. Warnings fail the build everywhere Incari code is compiled.

plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.errorprone) apply false
}

/**
 * Modules whose sources are all Incari code. `:client` is the exception: it compiles the
 * vendor tree together with Incari's adapters, so it configures its own compiler flags.
 */
val mixedVendorModules = setOf(":client")

subprojects {
    plugins.withType<JavaPlugin> {
        apply(plugin = "com.diffplug.spotless")
        apply(plugin = "net.ltgt.errorprone")

        extensions.configure<JavaPluginExtension> {
            // Minecraft 26.x targets Java 25, and TeaVM 0.16 reads class files of at least Java 25.
            toolchain.languageVersion = JavaLanguageVersion.of(25)
        }

        dependencies {
            "errorprone"(rootProject.libs.errorprone.core)
            "errorprone"(rootProject.libs.nullaway)
            "compileOnly"(rootProject.libs.jspecify)
            "testCompileOnly"(rootProject.libs.jspecify)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release = 25
            options.encoding = "UTF-8"
            if (project.path !in mixedVendorModules) {
                // -classfile: third-party jars (Guava in Mojang's set) reference annotation classes
                // they don't ship; javac's complaint about that isn't about Incari code.
                options.compilerArgs.addAll(listOf("-Xlint:all,-classfile", "-Werror"))
            }
            options.errorprone {
                // Null-safety is part of every Incari signature: unannotated means non-null,
                // and @Nullable (JSpecify) marks the exceptions.
                check("NullAway", if (name.contains("Test")) CheckSeverity.OFF else CheckSeverity.ERROR)
                option("NullAway:AnnotatedPackages", "incari")
                option("NullAway:JSpecifyMode", "true")
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        configure<SpotlessExtension> {
            java {
                // Incari sources only. The vendor tree lives in work/ and is never reformatted.
                target("src/*/java/**/*.java")
                palantirJavaFormat(rootProject.libs.versions.palantir.java.format.get())
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Module boundaries: gradle/module-boundaries.txt is the single list of allowed dependencies.
// ---------------------------------------------------------------------------------------------

val boundariesFile = layout.projectDirectory.file("gradle/module-boundaries.txt")

evaluationDependsOnChildren()

val declaredProjectDependencies: Provider<Map<String, Set<String>>> = provider {
    subprojects.filter { it.buildFile.exists() }.associate { p ->
        p.path to p.configurations.flatMap { configuration ->
            configuration.dependencies.withType<ProjectDependency>().map { it.path }
        }.filter { it != p.path }.toSet()
    }
}

val checkModuleBoundaries = tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Fails if a module depends on one gradle/module-boundaries.txt doesn't allow."
    val boundaries = providers.fileContents(boundariesFile).asText
    val declared = declaredProjectDependencies
    inputs.property("declared", declared)
    inputs.file(boundariesFile)
    doLast {
        val allowed = boundaries.get().lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                parts[0] to parts.drop(1).filter { it != "-" }.toSet()
            }
        val problems = mutableListOf<String>()
        for ((module, dependencies) in declared.get().toSortedMap()) {
            val allowedForModule = allowed[module]
            if (allowedForModule == null) {
                problems += "$module isn't listed in gradle/module-boundaries.txt"
                continue
            }
            for (dependency in dependencies.sorted()) {
                if (dependency !in allowedForModule) {
                    problems += "$module may not depend on $dependency (allowed: ${allowedForModule.sorted().ifEmpty { listOf("nothing") }.joinToString()})"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Module boundary violations (see gradle/module-boundaries.txt and ARCHITECTURE.md):\n" +
                    problems.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("Module boundaries hold for ${declared.get().size} modules.")
    }
}

// `./gradlew check` runs this and every module's own `check`.
tasks.register("check") {
    group = "verification"
    description = "Runs the repo-wide module boundary check."
    dependsOn(checkModuleBoundaries)
}
