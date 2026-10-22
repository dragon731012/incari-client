package incari.app.web;

import com.mojang.renderpearl.api.device.GpuBackend;
import incari.app.web.headless.HeadlessBackend;
import incari.classlib.concurrent.Waits;
import incari.classlib.resources.ClassPathResources;
import incari.client.TickPacing;
import incari.client.datafix.DataFixing;
import incari.client.gui.HudCache;
import incari.client.memory.LightCopies;
import incari.client.memory.PackedUnihex;
import incari.client.modules.IncariModules;
import incari.client.net.PacketHandoff;
import incari.client.net.PlatformEventLoopGroupHolder;
import incari.client.online.IncariOnline;
import incari.client.online.UpdateNotice;
import incari.client.particle.AmbientParticles;
import incari.client.particle.ParticleBudget;
import incari.client.particle.ParticleInstancing;
import incari.client.particle.ParticleLight;
import incari.client.platform.GraphicsBackends;
import incari.client.platform.OnlineServices;
import incari.client.profile.PlayerName;
import incari.client.profiler.Profiling;
import incari.client.render.AnimatedSprites;
import incari.client.render.EntityOcclusion;
import incari.client.render.GlyphQuads;
import incari.client.render.GraphUpdates;
import incari.client.render.QuadSort;
import incari.client.render.SignLines;
import incari.client.render.SpriteCache;
import incari.client.render.TerrainVertexFormat;
import incari.client.render.TextLayouts;
import incari.client.render.TintBlend;
import incari.client.render.VertexMath;
import incari.client.render.entity.ModelBatches;
import incari.client.render.entity.OpaqueSkins;
import incari.client.render.mesher.ChestBaking;
import incari.client.render.mesher.MeshTableCache;
import incari.client.render.mesher.Meshing;
import incari.client.render.terrain.TerrainDrawLists;
import incari.client.singleplayer.ChannelLink;
import incari.client.singleplayer.QuickWorlds;
import incari.client.singleplayer.WorkerWorlds;
import incari.client.startup.CriterionCodecs;
import incari.client.startup.DeferredModels;
import incari.client.startup.LoadingFade;
import incari.client.tier.DeviceTier;
import incari.client.world.BiomeCells;
import incari.client.world.ChunkCompression;
import incari.client.world.Collisions;
import incari.client.world.DensityBranches;
import incari.client.world.GoalFlags;
import incari.client.world.Heights;
import incari.client.world.PositionalRandoms;
import incari.client.world.SpawnCosts;
import incari.client.world.StrongholdRings;
import incari.client.world.SurfaceBuffers;
import incari.client.world.SurfaceWrites;
import incari.platform.api.Platforms;
import incari.platform.web.WebPlatform;
import incari.platform.web.storage.WebFileSystem;
import incari.render.webgl2.WebGl2Backend;
import incari.render.webgl2.shaders.ShaderManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.teavm.jso.JSBody;

/**
 * The browser build's entry point: opens the stored files ({@link WebFileSystem}), mounts the
 * client jar ({@link ClientJar}) and asset pack ({@link AssetPack}), loads the translated shaders
 * ({@link WebShaders}), installs {@link WebPlatform} and the WebGL2 backend, then starts the
 * game's {@code Main}. Each step shows on the loading screen ({@link LoaderPage}).
 *
 * <p>Page options, for development:
 *
 * <ul>
 *   <li>{@code ?renderer=headless}: run without drawing.
 *   <li>{@code ?shaders=eager}: link every WebGL program at start-up, so all compile errors show
 *       at once.
 *   <li>{@code ?server=ws://...}: join that server once started. {@code ?name=...}: the offline
 *       name.
 *   <li>{@code ?world=<name>}: open that singleplayer world; with {@code &seed=<seed>}, create it
 *       first (the singleplayer benchmark).
 *   <li>{@code ?share=<relay>}: share the open world on that relay. {@code ?shared=<relay>|<code>}:
 *       join a shared world (ADR 0062).
 *   <li>{@code ?options=<file>}: start with that options file from beside the page (the
 *       screenshot diffs pin their options this way).
 *   <li>{@code ?profile}: collect frame measurements from the start, for the benchmark.
 *       {@code ?overlay}: show the profiler's overlay.
 *   <li>{@code ?drawcensus}: log each pipeline's draws a frame every 600 frames.
 *       {@code ?gpucensus}: the same for GPU time.
 *   <li>{@code ?tier=potato|standard|high}: the device tier budgets scale by (particles, largest
 *       atlas, terrain heaps, decoded sounds). Standard is the default.
 *   <li>{@code ?mesher=inline|vanilla|verify}: mesh terrain on the page's thread, with the game's
 *       compiler, or both and compare, instead of in Incari's workers.
 * </ul>
 *
 * <p>To compare an Incari change with the game's own way:
 *
 * <ul>
 *   <li>Memory and start-up: {@code ?text=game} (lay out world text every frame),
 *       {@code ?quads=game}, {@code ?copies=all} (keep a copy of every WebGL buffer),
 *       {@code ?models=now} (load models before the title screen), {@code ?sprites=off} (decode
 *       every sprite).
 *   <li>{@code ?multidraw=off}: one draw per terrain section, not WEBGL_multi_draw (ADR 0022).
 *   <li>ADR 0027: {@code ?uniforms=each} (upload each uniform write alone), {@code ?rebase=off}
 *       (move the vertex attributes for each base vertex).
 *   <li>ADR 0030: {@code ?sort=game}, {@code ?tint=game}, {@code ?items=game} (no instancing),
 *       {@code ?skins=game}, {@code ?drawlists=game}, {@code ?faces=all} (draw back faces),
 *       {@code ?animations=all} (animate unseen sprites), {@code ?hud=live} (draw the HUD every
 *       frame), {@code ?chests=game} (closed chests as block entities).
 *   <li>ADR 0031: {@code ?ambient=game}, {@code ?glyphs=game}, {@code ?signs=game} (submit blank
 *       lines), {@code ?graph=game} (full occlusion update at once), {@code ?particlelight=frame}
 *       (read particle light every frame).
 * </ul>
 */
public final class WebMain {

    /** The background pool's least share of a frame while it has work (ADR 0035). */
    private static final double DEFAULT_POOL_FLOOR_MILLIS = 4.0;

    private WebMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("--worker")) {
            // A copy of the game in a worker (ADR 0039): singleplayer's server, never the client.
            // The page's query comes third, for the page options the server's code reads too.
            GameWorkerMain.main(args[1], args.length > 2 ? args[2] : "");
            return;
        }
        // Before any library reads them: Netty and JOML read their settings once, at class load.
        LibraryDefaults.apply();
        // This thread becomes the game's render thread: its waits hold back the page's frame, so
        // they're timed for the frame monitor.
        Waits.watch(Thread.currentThread());
        // A background pool task that blocks gives the pool up, so what it waits for can run.
        Waits.beforeWait(WebPlatform::beforeWait);
        // Before anything touches a file. Dropped files, the jar's resources and the sound
        // placeholders (rewritten from the asset index on every load) are never stored.
        LoaderPage.status("Opening saved files");
        WebFileSystem files = WebFileSystem.install(List.of(
                WebPlatform.DROP_DIRECTORY,
                ClassPathResources.ROOT,
                AssetPack.ROOT,
                WebStorage.DEFAULT.assetsDirectory()));
        LoaderPage.status("Loading game files");
        ClientJar.mount(files);
        LoaderPage.status("Loading textures");
        boolean pack = AssetPack.mount(files);
        if (pack && !"off".equals(option("sprites"))) {
            // The vanilla sprites the build decoded, with their mip levels (ADR 0026).
            SpriteCache.install(Path.of(AssetPack.ROOT, "sprites.bin"));
        }
        LoaderPage.status("Loading sounds");
        WebAssets.install(WebStorage.DEFAULT.assetsDirectory());
        Platforms.install(new WebPlatform(WebStorage.DEFAULT, files));
        WebCredits.install();
        // Realms is stripped from the browser build (tools/survey/rules.txt).
        OnlineServices.disableRealms();
        // No sockets, so no selector for Netty's event loops to wait on (ADR 0008).
        PlatformEventLoopGroupHolder.useLocalEventLoops();
        // Terrain meshing (ADR 0023): Incari's mesher in Web Workers, writing the compact
        // terrain vertex. ?mesher=inline meshes on the page's thread instead; ?mesher=vanilla uses
        // the game's compiler and vertex format; ?mesher=verify meshes both ways and compares.
        String mesher = option("mesher");
        switch (mesher) {
            case "vanilla" -> Meshing.configure(Meshing.Mode.VANILLA, false);
            case "verify" -> Meshing.configure(Meshing.Mode.VERIFY, false);
            default -> {
                TerrainVertexFormat.useCompact();
                Meshing.configure(Meshing.Mode.INCARI, !"inline".equals(mesher));
                // Translucent layers in an order that holds from every camera are never re-sorted
                // (ADR 0034); ?resort=game sorts them all as the game does.
                Meshing.setFixedOrder(!"game".equals(option("resort")));
                // Snapshots read each section's copied blocks directly (ADR 0034);
                // ?snapshotreads=game reads them a block at a time through the region.
                Meshing.setDirectReads(!"game".equals(option("snapshotreads")));
                // The mesher's tables kept from start to start (ADR 0032); ?meshtables=build builds
                // them every time.
                String build = LoaderPage.buildId();
                if (build != null && !"build".equals(option("meshtables"))) {
                    MeshTableCache.enable(Path.of("/incari/cache/mesher-tables.bin"), build);
                }
                // Closed chests meshed into terrain (ADR 0030); ?chests=game draws them the game's way.
                if (!"game".equals(option("chests"))) {
                    ChestBaking.enable();
                }
            }
        }
        // Entities, particles and text (ADR 0024): ?entityculling=off draws entities no visible
        // section holds, and ?instancing=off entity models the game's way, to compare; ?particles=potato,
        // standard or high picks the particle cap whatever the device's tier.
        EntityOcclusion.setEnabled(!"off".equals(option("entityculling")));
        if (!"off".equals(option("instancing")) && !"headless".equals(option("renderer"))) {
            ModelBatches.enable();
            // Particles too (ADR 0032); ?particlequads=game builds their vertices as the game does.
            if (!"game".equals(option("particlequads"))) {
                ParticleInstancing.enable();
            }
        }
        // The device's tier (not yet picked from the device): ?tier=potato, standard (the default) or high.
        DeviceTier tier = DeviceTier.named(option("tier"));
        DeviceTier.set(tier == null ? DeviceTier.STANDARD : tier);
        ParticleBudget.setTier(DeviceTier.named(option("particles")));
        // The memory diet (ADR 0025): the data fixer is built only if something old arrives.
        DataFixing.buildLazily();
        // Start-up (ADR 0026): the loading screen fades in half a second, not two, and the
        // title screen shows before the block, item and entity models are loaded.
        LoadingFade.set(250);
        if (!"now".equals(option("models"))) {
            DeferredModels.enable();
        }
        // The memory diet's reuse of world text and its baked-quad writer: ?text=game and
        // ?quads=game take the game's own paths, to compare.
        TextLayouts.setEnabled(!"game".equals(option("text")));
        VertexMath.setQuadsEnabled(!"game".equals(option("quads")));
        // ADR 0027's changes, each with the game's (or ADR 0025's) way to compare.
        QuadSort.setEnabled(!"game".equals(option("sort")));
        ModelBatches.setItemsEnabled(!"game".equals(option("items")));
        OpaqueSkins.setEnabled(!"game".equals(option("skins")));
        TintBlend.setEnabled(!"game".equals(option("tint")));
        // ADR 0030's terrain draw list and sprite animation, each with the game's way to compare.
        TerrainDrawLists.setEnabled(!"game".equals(option("drawlists")));
        TerrainDrawLists.setFacingCulling(!"all".equals(option("faces")));
        AnimatedSprites.setEnabled(!"all".equals(option("animations")));
        HudCache.setEnabled(!"live".equals(option("hud")));
        // Development: ?modules=minimap,coordinates (or all) turns those modules on at start.
        if (!option("modules").isEmpty()) {
            IncariModules.startWith(Arrays.asList(option("modules").split(",")));
        }
        // ADR 0031's changes, each with the game's way to compare.
        AmbientParticles.setEnabled(!"game".equals(option("ambient")));
        GlyphQuads.setEnabled(!"game".equals(option("glyphs")));
        SignLines.setEnabled(!"game".equals(option("signs")));
        GraphUpdates.setEnabled(!"game".equals(option("graph")));
        ParticleLight.setAcrossFrames(!"frame".equals(option("particlelight")));
        // ADR 0032's catch-up ticks spread over frames; ?ticks=game runs them back to back.
        TickPacing.setEnabled(!"game".equals(option("ticks")));
        // The background pool's least share of a frame while it has work (ADR 0035);
        // ?poolfloor=<ms> to compare, 1 being the old minimum.
        String poolFloor = option("poolfloor");
        WebPlatform.setPoolFloor(poolFloor.isEmpty() ? DEFAULT_POOL_FLOOR_MILLIS : Double.parseDouble(poolFloor));
        Collisions.setEnabled(!"game".equals(option("collisions")));
        // Singleplayer's server on the page (?singleplayer=page); the worker reads the same options.
        StrongholdRings.setEnabled(!"game".equals(option("rings")));
        SpawnCosts.setEnabled(!"game".equals(option("spawncosts")));
        SpawnCosts.setVerify("verify".equals(option("spawncosts")));
        // ADR 0041's world generation and loading changes, each with the game's way to compare.
        SurfaceWrites.setEnabled(!"game".equals(option("surface")));
        BiomeCells.setEnabled(!"game".equals(option("biomecells")));
        BiomeCells.setVerify("verify".equals(option("biomecells")));
        Heights.setEnabled(!"game".equals(option("heights")));
        CriterionCodecs.setEnabled(!"game".equals(option("criteria")));
        // Saved chunks deflated at zlib's fastest level (?deflate=game: its default).
        ChunkCompression.setEnabled(!"game".equals(option("deflate")));
        // The surface rules' positional randoms drawn without making them (?randoms=game: made).
        PositionalRandoms.setEnabled(!"game".equals(option("randoms")));
        SurfaceBuffers.setEnabled(!"game".equals(option("surfacebuffers")));
        // The Unicode font's glyphs packed into arrays (?unihex=game: an object each, as the game keeps them).
        PackedUnihex.setEnabled(!"game".equals(option("unihex")));
        // ADR 0042's lazy range choices (the server worker reads the same option).
        GoalFlags.setEnabled(!"game".equals(option("goalflags")));
        DensityBranches.setEnabled(!"game".equals(option("densitychoices")));
        // ADR 0041's packets a flush per message (?packets=each: one each, as before).
        ChannelLink.setBatched(!"each".equals(option("packets")));
        // ADR 0041's region files left in place for the worker (?inplace=off: handed over whole, as before).
        WebFileSystem.setInPlaceHandOver(!"off".equals(option("inplace")));
        // ADR 0032's light maps brought up to date in place; ?light=game copies them as the game does.
        LightCopies.setEnabled(!"game".equals(option("light")));
        LightCopies.setVerify("verify".equals(option("light")));
        // ADR 0033: packets their handlers only hand to the game's thread queued without the round
        // trip; ?handoff=game calls the handler on the network's thread first, as the game does.
        PacketHandoff.setEnabled(!"game".equals(option("handoff")));
        // ADR 0039: singleplayer's server in a worker; ?singleplayer=page runs it on the page, as ADR 0038 did.
        WorkerWorlds.setEnabled(!"page".equals(option("singleplayer")));
        WebGl2Backend.uploadEachUniformWrite("each".equals(option("uniforms")));
        WebGl2Backend.movePointersForQuadDraws("off".equals(option("rebase")));
        // Development: ?drawcensus logs each pipeline's draws a frame to the console.
        WebGl2Backend.countDrawsByPipeline(present("drawcensus"));
        // Development: ?gpucensus logs each pipeline's GPU time a frame (ADR 0031).
        WebGl2Backend.timeDrawsByPipeline(present("gpucensus"));
        // ?copies=all: every WebGL buffer keeps the game's copy, as before ADR 0025, to compare.
        WebGl2Backend.keepAllCopies("all".equals(option("copies")));
        if ("headless".equals(option("renderer"))) {
            GraphicsBackends.install(preference -> new GpuBackend[] {new HeadlessBackend()});
            GraphicsBackends.setRendering(false);
        } else {
            ShaderManifest shaders = WebShaders.load(pack);
            boolean eager = "eager".equals(option("shaders"));
            boolean multiDraw = !"off".equals(option("multidraw"));
            GraphicsBackends.install(preference ->
                    new GpuBackend[] {new WebGl2Backend(WebPlatform.CANVAS_ID, shaders, eager, multiDraw)});
        }
        if (present("profile")) {
            Profiling.collect(true);
        }
        if (present("overlay")) {
            Profiling.showOverlay(true);
        }
        String options = option("options");
        if (!options.isEmpty()) {
            // Development: start with these options (the screenshot-diff harness pins its scenes'
            // options this way), in place of whatever the game directory holds.
            Path gameDirectory = Path.of(WebStorage.DEFAULT.gameDirectory());
            Files.createDirectories(gameDirectory);
            Files.write(
                    gameDirectory.resolve("options.txt"), Fetch.bytes(options, "is the page serving " + options + "?"));
        }
        if (present("online")) {
            // Development: Incari accounts against another service (ADR 0058), ?online=ws://localhost:8080/v1/ws;
            // ?online= (empty) turns them off.
            IncariOnline.useUrl(option("online"));
        }
        if ("on".equals(option("updates"))) {
            // Testing: the title screen's update notice in a dev build too, ?updates=on (with
            // ?online= against tools/onlinedev --release <version>).
            UpdateNotice.force(true);
        }
        String name = option("name");
        if (!name.isEmpty() || !option("server").isEmpty()) {
            // The page names the player, or joins a server straight away: no Edit Profile first.
            PlayerName.fixAtLaunch();
        }
        List<String> arguments = new ArrayList<>(Arrays.asList(new String[] {
            "--version",
            ClientJar.VERSION,
            "--versionType",
            "incari-web",
            "--gameDir",
            WebStorage.DEFAULT.gameDirectory(),
            "--assetsDir",
            WebStorage.DEFAULT.assetsDirectory(),
            "--accessToken",
            "0",
            "--username",
            // A first start suggests a name of its own, as Eagler clients do; Edit Profile keeps it.
            name.isEmpty() ? PlayerName.suggest(new Random()) : name
        }));
        String server = option("server");
        if (!server.isEmpty()) {
            // Straight into a server, as a launcher's quick play does.
            arguments.addAll(List.of("--quickPlayMultiplayer", server));
        }
        String world = option("world");
        String seed = option("seed");
        if (!world.isEmpty() && !seed.isEmpty()) {
            // The singleplayer benchmark's world: made from the seed at the title screen, or opened.
            QuickWorlds.createAtTitle(world, Long.parseLong(seed));
        } else if (!world.isEmpty()) {
            arguments.addAll(List.of("--quickPlaySingleplayer", world));
        }
        String sharedWorld = option("shared");
        int bar = sharedWorld.lastIndexOf('|');
        if (bar > 0) {
            // The shared-world check's guest (ADR 0062): joins a shared world from the title screen.
            QuickWorlds.joinSharedAtTitle(sharedWorld.substring(0, bar), sharedWorld.substring(bar + 1));
        }
        String share = option("share");
        if (!share.isEmpty()) {
            // The shared-world check's host (ADR 0062): the world shared on this relay once it's open.
            incari.client.singleplayer.WorkerWorlds.shareWhenOpen(share);
        }
        LoaderPage.status("Starting the game");
        net.minecraft.client.main.Main.main(arguments.toArray(String[]::new));
    }

    /** A query parameter of the page's URL, or "" if it has none. */
    @JSBody(params = "name", script = "return new URLSearchParams(location.search).get(name) || '';")
    private static native String option(String name);

    /** Whether the page's URL has a query parameter, with or without a value. */
    @JSBody(params = "name", script = "return new URLSearchParams(location.search).has(name);")
    private static native boolean present(String name);
}
