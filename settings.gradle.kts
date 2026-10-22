rootProject.name = "incari"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // ViaVersion, ViaBackwards and ViaRewind, and nothing else.
        maven("https://repo.viaversion.com") {
            content { includeGroup("com.viaversion") }
        }
    }
}

// Every module and what it may depend on is listed in gradle/module-boundaries.txt;
// `./gradlew checkModuleBoundaries` (part of `check`) fails on anything else.

// Tools: the build CLI and its helpers. Run on a normal JVM, never shipped.
include(":tools:build")
include(":tools:survey")
include(":tools:copycheck")
include(":tools:webbuild")
include(":tools:teavm-patches")
include(":tools:shaders")
include(":tools:sprites")
include(":tools:wsbridge")
include(":tools:onlinedev")

// The platform layer (ARCHITECTURE.md, layer 1).
include(":platform-api")
include(":platform-desktop")
include(":platform-web")
// The JDK pieces TeaVM lacks, for the browser build.
include(":web-classlib")

// The game: the patched vendor tree from work/client plus the Incari code it calls.
include(":client")

// The terrain mesher: no game types, so it also compiles alone for the page's workers.
include(":mesher")

// Incari accounts and the online service, client side (ADR 0058): no game types.
include(":online")

// The module framework (ADR 0046) and the features built on it, one project each.
include(":framework")
include(":features:togglesprint")
include(":features:lowfire")
include(":features:nohurtcam")
include(":features:clearchat")
include(":features:armorpotionview")
include(":features:customscoreboard")
include(":features:waypoints")
include(":features:minimap")
include(":features:fpsdisplay")
include(":features:cps")
include(":features:reachdisplay")
include(":features:keystrokes")
include(":features:coordinates")
include(":features:shieldtotem")
include(":features:cooldownindicator")
include(":features:crosshair")
include(":features:freelook")
include(":features:zoom")
include(":features:saturation")
include(":features:weatherchanger")
include(":features:lowshield")
include(":features:shortswords")
include(":features:oldtextures")
include(":features:oldanimations")
include(":features:shulkertooltips")
include(":features:theme")
include(":features:blockoutline")
include(":features:hitcolor")
include(":features:glintcolor")
include(":features:hiteffects")
include(":features:fullbright")
include(":features:hitboxes")

// Graphics backends under the game's GpuDevice API (the desktop keeps Mojang's own).
include(":render-webgl2")

// The network transport under the game's Netty pipeline (WebSocket, the Eagler handshake).
include(":net")
// The Via stack in the client: talks to servers of other versions (ADR 0017).
include(":via")

// Entry points: one game, one platform each.
include(":app-desktop")
include(":app-web")
