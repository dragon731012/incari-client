package incari.app.desktop.smoke;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/**
 * A scripted play session that checks the desktop runtime end to end ("fully playable"):
 * boot, a new survival world, walking, looking, jumping, breaking and placing a block, the
 * inventory, chat, sound, and the network path (a LAN server pinged and joined through the
 * platform's transport). Input is injected as SDL events, so it takes the player's path.
 *
 * <p>Off unless {@code -Dincari.smoke=host} (plays and opens the world to LAN),
 * {@code -Dincari.smoke=host:<seconds>} (also waits that long for a second client to join), or
 * {@code -Dincari.smoke=join:<host:port>} (joins that world). Results
 * and screenshots go to {@code <gameDir>/incari-smoke/}. Development tooling, never in a build
 * players get.
 */
public final class SmokeTest {

    private static final long SEED = 20260926L;
    /** Longer than any scenario takes; past it, the game is stuck and the watchdog ends it. */
    private static final Duration TIME_LIMIT = Duration.ofMinutes(12);
    /** The mesher check meshes every section twice, over many places. */
    private static final Duration MESHER_TIME_LIMIT = Duration.ofMinutes(40);
    /** How long one query to the game thread is waited on before it's asked again. */
    private static final Duration QUERY_PATIENCE = Duration.ofSeconds(10);
    /** Fixed so a second client knows where to join. */
    static final int LAN_PORT = 25577;

    private final Minecraft minecraft;
    private final String mode;
    private final SmokeReport report;
    private final Path directory;
    private @Nullable SdlInjector input;
    /** Every chat line the chat window has shown, recorded by a pass-through display filter. */
    private final java.util.Set<String> seenChat = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private SmokeTest(Minecraft minecraft, String mode, Path directory) {
        this.minecraft = minecraft;
        this.mode = mode;
        this.directory = directory;
        this.report = new SmokeReport(directory);
    }

    /** Before the game starts: a scripted WebRTC for the voice scenario (the desktop has none). */
    public static void configurePlatform(incari.platform.desktop.DesktopPlatform platform) {
        if (System.getProperty("incari.smoke", "").startsWith("voice:")) {
            platform.setRtc(new ScriptedRtc());
        }
    }

    /** Starts the scenario named by {@code incari.smoke}, if any, on its own thread. */
    public static void startIfRequested(String gameDirectory) {
        String mode = System.getProperty("incari.smoke");
        if (mode == null || mode.isBlank()) {
            return;
        }
        Path directory = Path.of(gameDirectory, "incari-smoke");
        seedOptions(Path.of(gameDirectory));
        Thread.ofPlatform().name("Incari smoke test").daemon().start(() -> {
            Minecraft minecraft = waitForGame();
            new SmokeTest(minecraft, mode, directory).run();
        });
        // An unattended run must never leave a game window behind: if the game hangs (a stuck
        // server, a deadlock), end the process.
        Thread.ofPlatform().name("Incari smoke watchdog").daemon().start(() -> {
            Duration limit = mode.equals("mesher") ? MESHER_TIME_LIMIT : TIME_LIMIT;
            sleep(limit);
            org.slf4j.LoggerFactory.getLogger("Smoke test").error("Smoke test exceeded {}; halting", limit);
            Runtime.getRuntime().halt(3);
        });
    }

    /**
     * A fresh game directory would start with the accessibility onboarding screen and pause
     * whenever the window loses focus; neither suits an unattended run.
     */
    private static void seedOptions(Path gameDirectory) {
        Path options = gameDirectory.resolve("options.txt");
        if (java.nio.file.Files.exists(options)) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(gameDirectory);
            java.nio.file.Files.writeString(options, """
                            onboardAccessibility:false
                            pauseOnLostFocus:false
                            joinedFirstServer:true
                            skipMultiplayerWarning:true
                            tutorialStep:none
                            soundCategory_master:0.2
                            """);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Minecraft waitForGame() {
        while (true) {
            Minecraft instance = Minecraft.getInstance();
            if (instance != null) {
                return instance;
            }
            sleep(Duration.ofMillis(200));
        }
    }

    private void run() {
        try {
            if (mode.startsWith("join:")) {
                joinScenario(mode.substring("join:".length()));
            } else if (mode.startsWith("server:")) {
                serverScenario(mode.substring("server:".length()));
            } else if (mode.startsWith("voice:")) {
                String rest = mode.substring("voice:".length());
                int colon = rest.indexOf(':');
                voiceScenario(rest.substring(0, colon), rest.substring(colon + 1));
            } else if (mode.startsWith("legacy:")) {
                legacyScenario(mode.substring("legacy:".length()));
            } else if (mode.startsWith("auth:")) {
                authScenario(mode.substring("auth:".length()));
            } else if (mode.equals("screens")) {
                screensScenario();
            } else if (mode.equals("look")) {
                lookScenario();
            } else if (mode.equals("modules")) {
                modulesScenario();
            } else if (mode.equals("hudeditor")) {
                hudEditorScenario();
            } else if (mode.equals("features")) {
                featuresScenario();
            } else if (mode.equals("waypoints")) {
                waypointsScenario();
            } else if (mode.equals("hudmodules")) {
                hudModulesScenario();
            } else if (mode.equals("visualmodules")) {
                visualModulesScenario();
            } else if (mode.equals("screenmodules")) {
                screenModulesScenario();
            } else if (mode.equals("fit")) {
                fitScenario();
            } else if (mode.equals("layout")) {
                layoutScenario();
            } else if (mode.equals("cosmetics")) {
                cosmeticsScenario();
            } else if (mode.equals("mesher")) {
                mesherScenario();
            } else if (mode.startsWith("online:")) {
                String rest = mode.substring("online:".length());
                int colon = rest.indexOf(':');
                onlineScenario(rest.substring(0, colon), rest.substring(colon + 1));
            } else if (mode.startsWith("skins:")) {
                String rest = mode.substring("skins:".length());
                int colon = rest.indexOf(':');
                skinsScenario(rest.substring(0, colon), rest.substring(colon + 1));
            } else {
                hostScenario(mode.startsWith("host:") ? Integer.parseInt(mode.substring("host:".length())) : 0);
            }
        } catch (Throwable t) {
            report.fail("scenario", "stopped by " + t);
        } finally {
            report.finish();
            SdlInjector current = input;
            if (current != null) {
                current.close();
            }
            // stop() only clears a volatile flag, so it's safe from here even if the game is stuck.
            minecraft.stop();
            sleep(Duration.ofMinutes(1));
            // Still here: the game couldn't shut down. The report is written; end the process.
            Runtime.getRuntime().halt(report.passed() ? 0 : 1);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Scenarios
    // ----------------------------------------------------------------------------------------

    private void hostScenario(int waitForJoinSeconds) throws Exception {
        waitForTitle();
        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check(
                "singleplayer world", loaded, loaded ? "created and loaded a new survival world" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
        }
        if (!inWorld()) {
            return;
        }
        takeControl();
        screenshot("02-world");

        walk();
        jump();
        look();
        breakAndPlace();
        inventory();
        chat("hello from the incari smoke test");
        sound();
        lan(waitForJoinSeconds);

        injector().tap(41, 0x1B); // Escape opens the pause menu
        boolean paused = waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof PauseScreen);
        report.check("pause menu (Escape)", paused, "");
        clickButton("menu.returnToMenu");
        boolean left = waitUntil(
                Duration.ofMinutes(1), () -> minecraft.level == null && minecraft.gui.screen() instanceof TitleScreen);
        report.check("save and quit", left, left ? "back on the title screen" : stateDescription());
    }

    /** Creates and enters a new peaceful survival world from the smoke test's seed. */
    private void newSurvivalWorld() {
        onGameThread(() -> {
            String name = "incari-smoke-" + System.currentTimeMillis();
            LevelSettings settings = new LevelSettings(
                    name,
                    GameType.SURVIVAL,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                    true,
                    WorldDataConfiguration.DEFAULT);
            minecraft
                    .createWorldOpenFlows()
                    .createFreshLevel(
                            name,
                            settings,
                            new WorldOptions(SEED, true, false),
                            WorldPresets::createNormalWorldDimensions,
                            new TitleScreen());
            return null;
        });
    }

    /**
     * The client's look on the screens players see most, in both GUI presets, with the
     * pointer over a button (in Normal, mid-sweep): the title, options, video settings, the world
     * and server lists, the module menu; then in a world, the pause menu and the inventory.
     */
    private void lookScenario() throws Exception {
        waitForTitle();
        takeControl();
        boolean skinned = onGameThread(() -> incari.client.ui.Skins.current() != null);
        report.check("Incari's skin on", skinned, "");
        // The loading screen in the client's look (LoadingLook), mid-reload over the title: past
        // its fade in, before the reload's done.
        onGameThread(() -> {
            minecraft.gui.setScreen(new TitleScreen());
            var _ = minecraft.reloadResourcePacks(); // waited on through the overlay below
            return null;
        });
        sleep(Duration.ofMillis(700));
        boolean loading = onGameThread(() -> minecraft.gui.overlay() != null);
        screenshot("look-loading");
        report.check("Loading screen shown on a reload", loading, "");
        waitUntil(Duration.ofSeconds(30), () -> minecraft.gui.overlay() == null);
        for (incari.client.ui.GuiPreset preset : incari.client.ui.GuiPreset.values()) {
            String name = "look-" + preset.name().toLowerCase(java.util.Locale.ROOT);
            onGameThread(() -> {
                incari.client.ui.Look.set(preset);
                return null;
            });
            lookAt(new TitleScreen(), "menu.options", name + "-title");
            lookAt(
                    new net.minecraft.client.gui.screens.options.OptionsScreen(new TitleScreen(), minecraft.options),
                    "options.video",
                    name + "-options");
            lookAt(
                    new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                            new TitleScreen(), minecraft, minecraft.options),
                    "gui.done",
                    name + "-video");
            lookAt(
                    new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new TitleScreen()),
                    "selectServer.add",
                    name + "-servers");
            lookAt(
                    new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new TitleScreen()),
                    "selectWorld.create",
                    name + "-worlds");
            onGameThread(() -> {
                incari.client.modules.IncariModules.openMenu(new TitleScreen());
                return null;
            });
            sleep(Duration.ofSeconds(1));
            hoverButton("gui.done");
            sleep(Duration.ofMillis(250));
            screenshot(name + "-modules");
        }

        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        for (incari.client.ui.GuiPreset preset : incari.client.ui.GuiPreset.values()) {
            String name = "look-" + preset.name().toLowerCase(java.util.Locale.ROOT);
            onGameThread(() -> {
                incari.client.ui.Look.set(preset);
                return null;
            });
            lookAt(new PauseScreen(true), "menu.returnToGame", name + "-pause");
            lookAt(new InventoryScreen(player()), "", name + "-inventory");
            lookAt(
                    new net.minecraft.client.gui.screens.inventory.ContainerScreen(
                            net.minecraft.world.inventory.ChestMenu.threeRows(1, player().getInventory()),
                            player().getInventory(),
                            net.minecraft.network.chat.Component.literal("Chest")),
                    "",
                    name + "-chest");
            lookAt(
                    new net.minecraft.client.gui.screens.inventory.FurnaceScreen(
                            new net.minecraft.world.inventory.FurnaceMenu(1, player().getInventory()),
                            player().getInventory(),
                            net.minecraft.network.chat.Component.literal("Furnace")),
                    "",
                    name + "-furnace");
        }

        // The HUD: the hotbar, a scoreboard sidebar, chat with its field open, the tab list.
        serverCommand("give @s minecraft:diamond_sword");
        serverCommand("give @s minecraft:oak_planks 32");
        serverCommand("scoreboard objectives add incari dummy \"Incari\"");
        serverCommand("scoreboard objectives setdisplay sidebar incari");
        // Alone in singleplayer, the tab list shows only with an objective in it.
        serverCommand("scoreboard objectives setdisplay list incari");
        serverCommand("scoreboard players set Snow incari 12");
        serverCommand("scoreboard players set Frost incari 7");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            minecraft
                    .gui
                    .hud
                    .getChat()
                    .addClientSystemMessage(net.minecraft.network.chat.Component.literal("Welcome to Incari."));
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("look-hud");
        onGameThread(() -> {
            minecraft.gui.setScreen(new ChatScreen("hello", false));
            return null;
        });
        sleep(Duration.ofMillis(800));
        screenshot("look-chat");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });
        onGameThread(() -> {
            minecraft.options.keyPlayerList.setDown(true); // Tab held: the player list
            return null;
        });
        sleep(Duration.ofMillis(600));
        screenshot("look-tab");
        onGameThread(() -> {
            minecraft.options.keyPlayerList.setDown(false);
            return null;
        });
        boolean still = onGameThread(() -> incari.client.ui.Skins.current() != null);
        report.check("Incari's skin still on", still, still ? "" : "dropped: see the log");
        onGameThread(() -> {
            incari.client.ui.Look.set(incari.client.ui.GuiPreset.POTATO);
            minecraft.gui.setScreen(new PauseScreen(true));
            return null;
        });
        sleep(Duration.ofMillis(500));
        clickButton("menu.returnToMenu");
        waitUntil(
                Duration.ofMinutes(1), () -> minecraft.level == null && minecraft.gui.screen() instanceof TitleScreen);
    }

    /** Shows {@code screen}, puts the pointer over its button {@code hover}, and screenshots it. */
    private void lookAt(Screen screen, String hover, String name) {
        onGameThread(() -> {
            minecraft.gui.setScreen(screen);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        injector().moveTo(2, 2);
        sleep(Duration.ofMillis(200));
        if (!hover.isEmpty()) {
            hoverButton(hover);
        }
        // Normal's light sweep is part way across by now.
        sleep(Duration.ofMillis(250));
        screenshot(name);
    }

    /**
     * The module framework end to end. The module menu opens on its key; Toggle Sprint, turned
     * on, latches sprinting on a tap of the sprint key and says so on the HUD, and a second tap stops
     * it; the profile is saved to the game directory; the generated settings screen, the profiles
     * screen and the pause menu's Modules button are drawn.
     */
    private void modulesScenario() throws Exception {
        waitForTitle();
        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        takeControl();

        injector().tap(229, 0x400000E5); // Right Shift: the module menu
        boolean menu = waitUntil(Duration.ofSeconds(3), () -> screenName().equals("ModuleListScreen"));
        report.check("module menu (Right Shift)", menu, menu ? "" : stateDescription());
        screenshot("modules-menu");

        incari.framework.Module toggleSprint = onGameThread(() -> {
            incari.framework.Module module =
                    incari.client.modules.IncariModules.system().module("togglesprint");
            if (module == null) {
                throw new IllegalStateException("No togglesprint module");
            }
            module.setEnabled(true);
            incari.client.modules.IncariModules.openSettings(minecraft.gui.screen(), module);
            return module;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("modules-settings");
        onGameThread(() -> {
            incari.client.modules.IncariModules.openProfiles(minecraft.gui.screen());
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("modules-profiles");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            minecraft.mouseHandler.grabMouse();
            return null;
        });
        waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() == null);
        sleep(Duration.ofMillis(500));

        // A tap of Left Control latches sprinting: W alone then sprints.
        injector().tap(224, 0x400000E0);
        sleep(Duration.ofMillis(300));
        injector().key(44, ' ', true);
        injector().key(26, 'w', true);
        boolean sprinting = waitUntil(Duration.ofSeconds(3), () -> player().isSprinting());
        screenshot("modules-hud");
        injector().key(26, 'w', false);
        injector().key(44, ' ', false);
        report.check("toggle sprint latches", sprinting, sprinting ? "" : "W alone didn't sprint after a Control tap");

        injector().tap(224, 0x400000E0);
        sleep(Duration.ofMillis(300));
        injector().key(26, 'w', true);
        sleep(Duration.ofSeconds(1));
        boolean stopped = !onGameThread(() -> player().isSprinting());
        injector().key(26, 'w', false);
        report.check("a second tap unlatches", stopped, stopped ? "" : "still sprinting");

        // Saved a second after the change, under the game directory.
        sleep(Duration.ofMillis(1500));
        Path profile = directory.resolveSibling("incari/modules/profiles/Default.json");
        String saved = Files.isRegularFile(profile) ? Files.readString(profile) : "";
        boolean kept = saved.contains("\"togglesprint\"") && saved.contains("\"enabled\": true");
        report.check("profile saved", kept, kept ? profile.toString() : "not in " + profile);
        report.check("module still on", onGameThread(toggleSprint::isEnabled), "");

        injector().tap(41, 0x1B); // Escape opens the pause menu
        boolean paused = waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof PauseScreen);
        report.check("pause menu", paused, "");
        screenshot("modules-pause");
        clickButton("menu.returnToMenu");
        waitUntil(
                Duration.ofMinutes(1), () -> minecraft.level == null && minecraft.gui.screen() instanceof TitleScreen);
    }

    /**
     * ADR 0050: the first modules, in a world. Armor &amp; Potion View shows worn
     * diamond armor and three effects; Custom Scoreboard moves a server's sidebar from the game's
     * place to the top left, scaled; Clear Chat and No Hurt Cam hold their game options and give
     * them back, and moving Clear Chat's slider turns it off; Low Fire lowers the flames on a
     * burning player. Screenshots for each, and the HUD editor with them all.
     */
    private void featuresScenario() throws Exception {
        waitForTitle();
        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        takeControl();
        serverCommands(
                "time set noon",
                "item replace entity @p armor.head with diamond_helmet[damage=40]",
                "item replace entity @p armor.chest with diamond_chestplate[damage=300]",
                "item replace entity @p armor.legs with iron_leggings[damage=200]",
                "item replace entity @p armor.feet with diamond_boots[damage=410]",
                "item replace entity @p weapon.mainhand with diamond_sword[damage=500]",
                "effect give @p speed 83 1",
                "effect give @p fire_resistance infinite 0",
                "effect give @p strength 8 0",
                "scoreboard objectives add kills dummy \"Incari Test\"",
                "scoreboard objectives setdisplay sidebar kills",
                "scoreboard players set Kills kills 12",
                "scoreboard players set Deaths kills 3",
                "scoreboard players set Coins kills 250",
                "say Clear Chat: no box behind these lines",
                "say A second line");
        sleep(Duration.ofSeconds(1));
        screenshot("features-off");

        // The tab list's header, players and footer as one card, no dark band where they meet.
        serverCommands("scoreboard objectives setdisplay list kills");
        onGameThread(() -> {
            var tabList = minecraft.gui.hud.getTabList();
            tabList.setHeader(net.minecraft.network.chat.Component.literal("Incari Test\n\nA second header line"));
            tabList.setFooter(net.minecraft.network.chat.Component.literal("A footer line\nAnother"));
            minecraft.options.keyPlayerList.setDown(true);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("features-tab-list");
        onGameThread(() -> {
            minecraft.options.keyPlayerList.setDown(false);
            return null;
        });
        serverCommands("scoreboard objectives setdisplay list");

        incari.framework.ModuleSystem system = onGameThread(incari.client.modules.IncariModules::system);
        String[] ids = {"armorpotionview", "customscoreboard", "clearchat", "lowfire", "nohurtcam"};
        double opacityBefore =
                onGameThread(() -> minecraft.options.textBackgroundOpacity().get());
        double tiltBefore =
                onGameThread(() -> minecraft.options.damageTiltStrength().get());
        onGameThread(() -> {
            for (String id : ids) {
                incari.framework.Module module = system.module(id);
                if (module == null) {
                    throw new IllegalStateException("No module " + id);
                }
                module.setEnabled(true);
            }
            return null;
        });
        serverCommands("say Clear Chat: no box behind these lines");
        sleep(Duration.ofSeconds(1));
        screenshot("features-on");
        report.check(
                "clear chat holds the background opacity at 0",
                onGameThread(() -> minecraft.options.textBackgroundOpacity().get() == 0.0),
                "was " + opacityBefore);
        report.check(
                "no hurt cam holds damage tilt at 0",
                onGameThread(() -> minecraft.options.damageTiltStrength().get() == 0.0),
                "was " + tiltBefore);
        report.check(
                "the sidebar is the module's",
                onGameThread(() -> system.holds(incari.framework.game.GamePart.SCOREBOARD)),
                "");

        incari.framework.Module scoreboard = system.module("customscoreboard");
        if (scoreboard != null) {
            incari.framework.hud.HudElement sidebar = scoreboard.hudElements().get(0);
            onGameThread(() -> {
                sidebar.anchor().set(incari.framework.hud.Anchor.TOP_LEFT);
                sidebar.offsetX().set(4);
                sidebar.offsetY().set(4);
                sidebar.scale().set(1.5F);
                return null;
            });
            sleep(Duration.ofSeconds(1));
            screenshot("features-scoreboard-moved");
        }

        // Burning, in first person: the flames drawn lower, then where the game draws them.
        onGameThread(() -> {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server != null) {
                server.execute(() -> server.getPlayerList().getPlayers().get(0).setRemainingFireTicks(1200));
            }
            return null;
        });
        boolean burning = waitUntil(Duration.ofSeconds(5), () -> player().isOnFire());
        report.check("player on fire", burning, "");
        incari.framework.Module lowFire = system.module("lowfire");
        if (lowFire != null) {
            sleep(Duration.ofMillis(1500));
            onGameThread(() -> {
                lowFire.setEnabled(false);
                return null;
            });
            sleep(Duration.ofMillis(500));
            for (int frame = 0; frame < 6; frame++) {
                screenshot("features-fire-game-" + frame);
            }
            incari.framework.setting.IntSetting lower =
                    (incari.framework.setting.IntSetting) lowFire.settings().get("lower");
            for (int percent : new int[] {10, 20, 30}) {
                onGameThread(() -> {
                    lowFire.setEnabled(true);
                    if (lower != null) {
                        lower.set(percent);
                    }
                    return null;
                });
                sleep(Duration.ofMillis(300));
                for (int frame = 0; frame < 6; frame++) {
                    screenshot("features-fire-low-" + percent + "-" + frame);
                }
            }
        }

        onGameThread(() -> {
            incari.client.modules.IncariModules.openHudEditor(null);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("features-hudeditor");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });

        // The slider is the player's: moving it turns Clear Chat off and keeps the new value.
        onGameThread(() -> {
            minecraft.options.textBackgroundOpacity().set(0.3);
            return null;
        });
        incari.framework.Module clearChat = system.module("clearchat");
        boolean letGo = clearChat != null && waitUntil(Duration.ofSeconds(3), () -> !clearChat.isEnabled());
        report.check(
                "moving the slider turns clear chat off",
                letGo
                        && onGameThread(
                                () -> minecraft.options.textBackgroundOpacity().get() == 0.3),
                "");
        onGameThread(() -> {
            incari.framework.Module noHurtCam = system.module("nohurtcam");
            if (noHurtCam != null) {
                noHurtCam.setEnabled(false);
            }
            return null;
        });
        double tiltAfter =
                onGameThread(() -> minecraft.options.damageTiltStrength().get());
        report.check("no hurt cam gives damage tilt back", Math.abs(tiltAfter - tiltBefore) < 0.01, "now " + tiltAfter);
        oldTexturesChecks(system);
        sleep(Duration.ofMillis(1500));
        system.flush();
    }

    /** ADR 0055: Old Textures is Programmer Art's switch, and the Resource Packs screen is its too. */
    private void oldTexturesChecks(incari.framework.ModuleSystem system) {
        incari.framework.Module oldTextures = system.module("oldtextures");
        if (oldTextures == null) {
            report.check("old textures module", false, "no module oldtextures");
            return;
        }
        onGameThread(() -> {
            oldTextures.setEnabled(true);
            return null;
        });
        boolean packOn =
                waitUntil(Duration.ofSeconds(2), () -> minecraft.options.resourcePacks.contains("programmer_art"));
        boolean reloaded = waitUntil(Duration.ofSeconds(60), () -> minecraft.gui.overlay() == null);
        // Under Incari's pack, which is fixed on top, as the Resource Packs screen puts it.
        java.util.List<String> order = onGameThread(() ->
                java.util.List.copyOf(minecraft.getResourcePackRepository().getSelectedIds()));
        boolean under =
                order.indexOf("programmer_art") >= 0 && order.indexOf("programmer_art") < order.indexOf("incari");
        report.check(
                "old textures turns programmer art on",
                packOn && reloaded && oldTextures.isEnabled() && under,
                "packs " + order);
        screenshot("features-old-textures");

        // As the Resource Packs screen leaves it: the repository's selection, then the options.
        onGameThread(() -> {
            var packs = minecraft.getResourcePackRepository();
            packs.removePack("programmer_art");
            minecraft.options.updateResourcePacks(packs);
            return null;
        });
        boolean followedOff = waitUntil(Duration.ofSeconds(3), () -> !oldTextures.isEnabled());
        waitUntil(Duration.ofSeconds(60), () -> minecraft.gui.overlay() == null);
        onGameThread(() -> {
            var packs = minecraft.getResourcePackRepository();
            packs.addPack("programmer_art");
            minecraft.options.updateResourcePacks(packs);
            return null;
        });
        boolean followedOn = waitUntil(Duration.ofSeconds(3), oldTextures::isEnabled);
        waitUntil(Duration.ofSeconds(60), () -> minecraft.gui.overlay() == null);
        report.check(
                "the resource packs screen turns old textures",
                followedOff && followedOn,
                "off " + followedOff + ", on " + followedOn);

        onGameThread(() -> {
            oldTextures.setEnabled(false);
            return null;
        });
        boolean packOff =
                waitUntil(Duration.ofSeconds(2), () -> !minecraft.options.resourcePacks.contains("programmer_art"));
        waitUntil(Duration.ofSeconds(60), () -> minecraft.gui.overlay() == null);
        report.check("old textures turns programmer art off", packOff, "");
        screenshot("features-new-textures");
    }

    /**
     * ADR 0052: Keystrokes, CPS, FPS Display, Reach Display, Coordinates and Shield &amp; Totem HUD in
     * a world: keys held and clicks on a mob in reach, a shield and totems carried.
     */
    private void hudModulesScenario() throws Exception {
        waitForTitle();
        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        takeControl();
        serverCommands(
                "time set noon",
                "gamerule doMobSpawning false",
                "execute as @p at @s run tp @s ~ ~ ~ 180 0",
                "item replace entity @p weapon.offhand with shield[damage=200]",
                "give @p totem_of_undying 2",
                "execute at @p run summon villager ~ ~ ~-2.6 {NoAI:1b,Invulnerable:1b,Rotation:[0f,0f]}");
        sleep(Duration.ofSeconds(1));

        incari.framework.ModuleSystem system = onGameThread(incari.client.modules.IncariModules::system);
        String[] ids = {"keystrokes", "cps", "fpsdisplay", "reachdisplay", "coordinates", "shieldtotem"};
        java.util.List<Double> reaches = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<Integer> clicks = new java.util.concurrent.CopyOnWriteArrayList<>();
        incari.framework.event.AttackListener attackProbe = reaches::add;
        incari.framework.event.ClickListener clickProbe = clicks::add;
        onGameThread(() -> {
            for (String id : ids) {
                incari.framework.Module module = system.module(id);
                if (module == null) {
                    throw new IllegalStateException("No module " + id);
                }
                module.setEnabled(true);
            }
            system.events().add(incari.framework.event.Events.ATTACK, this, attackProbe);
            system.events().add(incari.framework.event.Events.CLICK, this, clickProbe);
            return null;
        });
        for (int i = 0; i < 6; i++) {
            injector().mouseButton(1, true);
            sleep(Duration.ofMillis(40));
            injector().mouseButton(1, false);
            sleep(Duration.ofMillis(110));
        }
        injector().mouseButton(3, true);
        sleep(Duration.ofMillis(40));
        injector().mouseButton(3, false);
        // W and space held while the HUD is shot: their keys lit.
        injector().key(26, 'w', true);
        injector().key(44, ' ', true);
        sleep(Duration.ofMillis(200));
        screenshot("hudmodules-on");
        injector().key(26, 'w', false);
        injector().key(44, ' ', false);
        report.check("clicks reach modules", clicks.size() >= 7, clicks.size() + " clicks");
        report.check(
                "hits report their reach",
                !reaches.isEmpty() && reaches.get(0) > 1.5 && reaches.get(0) < 3.5,
                reaches.toString());
        incari.framework.game.Game game = system.game();
        report.check("fps read", onGameThread(game::fps) > 0, "");
        report.check(
                "W bound as W",
                onGameThread(() -> game.keyName(incari.framework.game.GameKey.FORWARD))
                        .equals("W"),
                onGameThread(() -> game.keyName(incari.framework.game.GameKey.FORWARD)));
        report.check(
                "attack bound as LMB",
                onGameThread(() -> game.keyName(incari.framework.game.GameKey.ATTACK))
                        .equals("LMB"),
                onGameThread(() -> game.keyName(incari.framework.game.GameKey.ATTACK)));
        report.check(
                "totems counted",
                onGameThread(() -> {
                            incari.framework.game.PlayerView view = game.player();
                            return view == null ? -1 : view.count("minecraft:totem_of_undying");
                        })
                        == 2,
                "");
        report.check(
                "shield seen in the off hand",
                onGameThread(() -> {
                    incari.framework.game.PlayerView view = game.player();
                    return view != null
                            && view.item(incari.framework.game.EquipmentSlot.OFFHAND)
                                    .id()
                                    .equals("minecraft:shield");
                }),
                "");
        String biome = onGameThread(() -> {
            incari.framework.game.PlayerView view = game.player();
            return view == null ? "" : view.biome();
        });
        report.check("biome named", !biome.isEmpty() && !biome.startsWith("biome."), biome);

        onGameThread(() -> {
            system.events().remove(incari.framework.event.Events.ATTACK, attackProbe);
            system.events().remove(incari.framework.event.Events.CLICK, clickProbe);
            incari.client.modules.IncariModules.openHudEditor(null);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("hudmodules-hudeditor");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });
        sleep(Duration.ofMillis(500));
        system.flush();
    }

    /**
     * ADR 0053: Crosshair Editor's presets and grid, Cooldown Indicator after a swing, Weather
     * Changer over a server's rain (clear, snow, midnight), Freelook with Left Alt held, Low Shield,
     * and 1.7 Animations' poses and block-hit.
     */
    private void visualModulesScenario() throws Exception {
        waitForTitle();
        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        takeControl();
        serverCommands(
                "time set noon",
                "gamerule doDaylightCycle false",
                "execute as @p at @s run tp @s ~ ~ ~ 180 10",
                "item replace entity @p weapon.mainhand with diamond_sword",
                "item replace entity @p weapon.offhand with shield");
        sleep(Duration.ofSeconds(1));
        incari.framework.ModuleSystem system = onGameThread(incari.client.modules.IncariModules::system);

        // Crosshair Editor: each preset, then the grid.
        incari.framework.Module crosshair = module(system, "crosshair");
        screenshot("visual-crosshair-game");
        onGameThread(() -> {
            crosshair.setEnabled(true);
            return null;
        });
        for (String each : new String[] {"classic", "competitive", "small", "dot", "circle", "t_shape"}) {
            choose(crosshair, "preset", each);
            sleep(Duration.ofMillis(300));
            screenshot("visual-crosshair-" + each);
        }
        choose(crosshair, "style", "grid");
        sleep(Duration.ofMillis(300));
        screenshot("visual-crosshair-grid");
        choose(crosshair, "preset", "competitive");

        // Cooldown Indicator: the game's own off, the bar charging after a swing.
        incari.framework.Module cooldown = module(system, "cooldownindicator");
        onGameThread(() -> {
            cooldown.setEnabled(true);
            return null;
        });
        report.check(
                "cooldown indicator holds the game's indicator off",
                onGameThread(() ->
                        minecraft.options.attackIndicator().get() == net.minecraft.client.AttackIndicatorStatus.OFF),
                "");
        injector().mouseButton(1, true);
        sleep(Duration.ofMillis(30));
        injector().mouseButton(1, false);
        sleep(Duration.ofMillis(150));
        screenshot("visual-cooldown-bar");
        choose(cooldown, "style", "ring");
        sleep(Duration.ofMillis(800));
        injector().mouseButton(1, true);
        sleep(Duration.ofMillis(30));
        injector().mouseButton(1, false);
        sleep(Duration.ofMillis(200));
        screenshot("visual-cooldown-ring");
        onGameThread(() -> {
            cooldown.setEnabled(false);
            return null;
        });
        report.check(
                "cooldown indicator gives the game's indicator back",
                onGameThread(() ->
                        minecraft.options.attackIndicator().get() != net.minecraft.client.AttackIndicatorStatus.OFF),
                "");

        // Weather Changer over the server's rain.
        serverCommands("weather rain");
        waitUntil(Duration.ofSeconds(15), () -> onGameThread(() -> level().getRainLevel(1.0F) > 0.9F));
        screenshot("visual-weather-rain");
        incari.framework.Module weather = module(system, "weatherchanger");
        choose(weather, "weather", "clear");
        onGameThread(() -> {
            ((incari.framework.setting.BooleanSetting)
                            java.util.Objects.requireNonNull(weather.settings().get("hold-time")))
                    .set(false);
            weather.setEnabled(true);
            return null;
        });
        sleep(Duration.ofMillis(500));
        report.check(
                "weather changer clears the rain",
                onGameThread(() -> incari.client.modules.ModuleSky.shownRain(level(), 1.0F) == 0.0F),
                "");
        // Only where it's drawn: the game's own rain checks (a trident's Riptide) see the real.
        report.check("the client level keeps the real rain", onGameThread(() -> level().isRaining()), "");
        boolean serverStillRains = onGameThread(() -> {
            IntegratedServer server = minecraft.getSingleplayerServer();
            return server != null && server.overworld().isRaining();
        });
        report.check("the server's rain goes on", serverStillRains, "");
        screenshot("visual-weather-clear");
        choose(weather, "weather", "snow");
        onGameThread(() -> {
            ((incari.framework.setting.BooleanSetting)
                            java.util.Objects.requireNonNull(weather.settings().get("hold-time")))
                    .set(true);
            ((incari.framework.setting.IntSetting)
                            java.util.Objects.requireNonNull(weather.settings().get("hour")))
                    .set(0);
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("visual-weather-snow-midnight");
        onGameThread(() -> {
            weather.setEnabled(false);
            return null;
        });
        sleep(Duration.ofMillis(300));
        report.check("weather changer gives the rain back", onGameThread(() -> level().getRainLevel(1.0F) > 0.9F), "");
        serverCommands("weather clear");

        // Freelook: Left Alt held, the mouse turns the camera and not the player.
        incari.framework.Module freelook = module(system, "freelook");
        onGameThread(() -> {
            freelook.setEnabled(true);
            return null;
        });
        float yawBefore = onGameThread(() -> player().getYRot());
        // The game turns only while its window is active (as look() finds).
        injector().focus();
        sleep(Duration.ofMillis(100));
        injector().key(226, 0x400000E2, true);
        sleep(Duration.ofMillis(300));
        for (int i = 0; i < 10; i++) {
            injector().look(40, 0);
            sleep(Duration.ofMillis(30));
        }
        sleep(Duration.ofMillis(300));
        float yawDuring = onGameThread(() -> player().getYRot());
        float cameraYaw = onGameThread(() -> minecraft.gameRenderer.mainCamera().yRot());
        boolean third = onGameThread(
                () -> minecraft.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        screenshot("visual-freelook");
        injector().key(226, 0x400000E2, false);
        sleep(Duration.ofMillis(400));
        boolean first = onGameThread(() -> minecraft.options.getCameraType().isFirstPerson());
        report.check(
                "freelook turns the camera, not the player",
                Math.abs(yawDuring - yawBefore) < 0.01F && Math.abs(cameraYaw - yawBefore) > 5,
                "player " + yawBefore + " -> " + yawDuring + ", camera " + cameraYaw);
        report.check("freelook looks from behind, then back", third && first, "third " + third + ", first " + first);
        onGameThread(() -> {
            freelook.setEnabled(false);
            return null;
        });

        // Hitboxes: a villager (the world may be peaceful) under the crosshair and a cow beside it.
        // On flat ground cleared in front (north), so neither stands in the hillside.
        serverCommands(
                "execute as @p at @s run fill ~-5 ~-1 ~-8 ~5 ~-1 ~2 stone",
                "execute as @p at @s run fill ~-5 ~ ~-8 ~5 ~4 ~2 air",
                "execute as @p at @s run tp @s ~ ~ ~ 180 10",
                "execute as @p at @s run summon villager ^ ^ ^3 {NoAI:1b,Silent:1b,Rotation:[0f,0f]}",
                "execute as @p at @s run summon cow ^2 ^ ^4 {NoAI:1b,Silent:1b}");
        sleep(Duration.ofSeconds(1));
        screenshot("visual-hitboxes-game");
        incari.framework.Module hitboxes = module(system, "hitboxes");
        onGameThread(() -> {
            hitboxes.settings().resetAll();
            hitboxes.setEnabled(true);
            return null;
        });
        sleep(Duration.ofMillis(300));
        incari.framework.game.Hitboxes held = onGameThread(incari.client.modules.ModuleHitboxes::held);
        report.check("hitboxes reach the renderer", held != null, String.valueOf(held));
        boolean aimed = onGameThread(
                () -> minecraft.crosshairPickEntity instanceof net.minecraft.world.entity.npc.villager.Villager);
        report.check(
                "the crosshair is on the villager",
                aimed,
                String.valueOf(onGameThread(() -> minecraft.crosshairPickEntity)));
        screenshot("visual-hitboxes-on");
        choose(hitboxes, "show", "players");
        onGameThread(() -> {
            ((incari.framework.setting.BooleanSetting)
                            java.util.Objects.requireNonNull(hitboxes.settings().get("own")))
                    .set(true);
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            return null;
        });
        sleep(Duration.ofMillis(500));
        held = onGameThread(incari.client.modules.ModuleHitboxes::held);
        report.check(
                "a hitboxes setting reaches the renderer at once",
                held != null && held.show() == incari.framework.game.Hitboxes.Show.PLAYERS && held.own(),
                String.valueOf(held));
        screenshot("visual-hitboxes-own");
        onGameThread(() -> {
            minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            hitboxes.setEnabled(false);
            return null;
        });
        report.check("hitboxes off draws none", onGameThread(incari.client.modules.ModuleHitboxes::held) == null, "");
        serverCommands("kill @e[type=villager]", "kill @e[type=cow]");

        // Low Shield and 1.7 Animations: the hands without, then with.
        serverCommands("execute as @p at @s run tp @s ~ ~ ~ 180 10");
        sleep(Duration.ofMillis(500));
        screenshot("visual-hands-game");
        incari.framework.Module lowShield = module(system, "lowshield");
        incari.framework.Module old = module(system, "oldanimations");
        onGameThread(() -> {
            lowShield.setEnabled(true);
            old.setEnabled(true);
            return null;
        });
        sleep(Duration.ofMillis(500));
        screenshot("visual-hands-modules");
        serverCommands("item replace entity @p weapon.mainhand with fishing_rod");
        sleep(Duration.ofMillis(800));
        screenshot("visual-hands-rod");
        onGameThread(() -> {
            old.setEnabled(false);
            return null;
        });
        sleep(Duration.ofMillis(300));
        screenshot("visual-hands-rod-game");
        // A sword that blocks (as on a 1.8 server): 1.7's pose, then the block-hit on a block.
        serverCommands(
                "item replace entity @p weapon.offhand with air",
                "item replace entity @p weapon.mainhand with diamond_sword[blocks_attacks={}]",
                "execute as @p at @s run tp @s ~ ~ ~ 180 70");
        sleep(Duration.ofMillis(800));
        injector().mouseButton(3, true);
        sleep(Duration.ofMillis(500));
        screenshot("visual-block-game");
        onGameThread(() -> {
            old.setEnabled(true);
            return null;
        });
        sleep(Duration.ofMillis(300));
        screenshot("visual-block-old");
        boolean using = onGameThread(() -> player().isUsingItem());
        injector().mouseButton(1, true);
        boolean swung = waitUntil(Duration.ofSeconds(2), () -> onGameThread(() -> player().getCurrentSwing() != null));
        sleep(Duration.ofMillis(100));
        screenshot("visual-block-hit");
        boolean stillUsing = onGameThread(() -> player().isUsingItem());
        injector().mouseButton(1, false);
        injector().mouseButton(3, false);
        report.check("blocking with the sword", using, "");
        report.check(
                "block-hit swings while blocking",
                swung && stillUsing,
                "swung " + swung + ", still using " + stillUsing);
        onGameThread(() -> {
            lowShield.setEnabled(false);
            old.setEnabled(false);
            crosshair.setEnabled(false);
            return null;
        });
        sleep(Duration.ofMillis(500));
        system.flush();
    }

    /**
     * Text that must fit its box: the screens whose long or player-chosen text used to run past
     * its panel, at GUI scale 2, 3 and 4 in a 1366x768 window (a Chromebook windowed and
     * fullscreen). The game caps the scale at what leaves 320x240 units, so 4 there is 3 (455x256);
     * narrower layouts are seen in smaller framebuffers at 3: 1280x720 (426x240) and 1024x768
     * (341x256), where the module menu, the screenshot gallery, the pause card and the menus'
     * captions must still fit. Screenshots only; a person looks at them.
     */
    private void fitScenario() throws Exception {
        waitForTitle();
        takeControl();
        incari.framework.ModuleSystem system = onGameThread(incari.client.modules.IncariModules::system);
        onGameThread(
                () -> incari.client.online.IncariOnline.service().wardrobe().saveOutfit("My Practice Server Setup"));
        for (int scale : new int[] {2, 3, 4}) {
            String name = "fit-" + scale;
            guiScale(scale);
            onGameThread(() -> system.applyPreset("Modern PvP", java.util.Set.of("shieldtotem", "lowshield")));
            lookAt(menu(), "", name + "-modules-preset");
            onGameThread(() -> system.applyPreset("My Practice Server Setup", java.util.Set.of("minimap")));
            lookAt(menu(), "", name + "-modules-long-profile");
            onGameThread(() -> {
                incari.client.modules.IncariModules.openProfiles(new TitleScreen());
                return null;
            });
            sleep(Duration.ofSeconds(1));
            screenshot(name + "-profiles");
            lookAt(new incari.client.settings.IncariSettingsScreen(new TitleScreen()), "", name + "-settings");
            hoverLabel("Show others I use Incari");
            sleep(Duration.ofMillis(400));
            screenshot(name + "-settings-tooltip");
            lookAt(
                    new incari.client.eagler.PasswordScreen(
                            new TitleScreen(),
                            new ServerData("Smoke", "localhost", ServerData.Type.OTHER),
                            ServerAddress.parseString("localhost"),
                            new incari.client.eagler.EaglerAuth.Request(
                                    "localhost",
                                    incari.net.eagler.EaglerPassword.PLAINTEXT,
                                    "Welcome back to the server. Log in with the password you registered with"
                                            + " last time. If you forgot it, ask a staff member on Discord to reset"
                                            + " it for you.")),
                    "",
                    name + "-password");
            lookAt(incari.client.cosmetics.WardrobeScreen.showing(null, null), "", name + "-wardrobe-outfits");
            lookAt(
                    incari.client.cosmetics.WardrobeScreen.showing(null, incari.online.Cosmetic.Slot.EMOTE),
                    "",
                    name + "-wardrobe-emotes");
            lookAt(
                    incari.client.cosmetics.WardrobeScreen.showing(null, incari.online.Cosmetic.Slot.NAMETAG),
                    "",
                    name + "-wardrobe-nametags");
            var constructor = Class.forName("incari.client.online.ForgotPasswordScreen")
                    .asSubclass(Screen.class)
                    .getDeclaredConstructor(Screen.class);
            constructor.setAccessible(true);
            Screen forgot = constructor.newInstance(new TitleScreen());
            onGameThread(() -> {
                minecraft.gui.setScreen(forgot);
                var status = Class.forName("incari.client.online.FormScreen")
                        .getDeclaredMethod("status", String.class, int.class);
                status.setAccessible(true);
                status.invoke(
                        forgot,
                        "Wrong username or recovery code. Check both and try again in a few minutes.",
                        incari.client.ui.Theme.NEGATIVE);
                return null;
            });
            sleep(Duration.ofSeconds(1));
            screenshot(name + "-forgot-password");
        }

        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        onGameThread(() -> {
            incari.framework.Module minimap = system.module("minimap");
            if (minimap != null) {
                minimap.setEnabled(true);
            }
            return null;
        });
        for (int scale : new int[] {2, 3, 4}) {
            String name = "fit-" + scale;
            guiScale(scale);
            lookAt(incari.client.profile.ProfileScreen.from(new PauseScreen(true)), "", name + "-edit-profile");
            onGameThread(() -> {
                minecraft.gui.setScreen(null);
                return null;
            });
            sleep(Duration.ofMillis(500));
            injector().key(16, 'm', true);
            sleep(Duration.ofMillis(150));
            injector().key(16, 'm', false);
            sleep(Duration.ofSeconds(2));
            screenshot(name + "-worldmap");
            onGameThread(() -> {
                minecraft.gui.setScreen(null);
                return null;
            });
        }

        // A picture for the gallery, then the narrow windows.
        injector().tap(59, 0x4000003B);
        sleep(Duration.ofMillis(1500));
        for (int[] size : new int[][] {{1280, 720}, {1024, 768}}) {
            if (!windowSize(size[0], size[1])) {
                continue;
            }
            guiScale(3);
            String name =
                    "fit-narrow-" + onGameThread(() -> minecraft.getWindow().getGuiScaledWidth()) + "x"
                            + onGameThread(() -> minecraft.getWindow().getGuiScaledHeight());
            lookAt(menu(), "", name + "-modules");
            lookAt(new incari.client.settings.IncariSettingsScreen(new TitleScreen()), "", name + "-settings");
            lookAt(new incari.client.recorder.RecorderScreen(null), "", name + "-recorder");
            onGameThread(() -> {
                incari.client.modules.IncariModules.openSettings(
                        new TitleScreen(), java.util.Objects.requireNonNull(system.module("theme")));
                return null;
            });
            sleep(Duration.ofSeconds(1));
            screenshot(name + "-theme");
            onGameThread(() -> {
                system.game().askText("Import theme", "Paste a theme code (Ctrl+V), then press Done.", "", text -> {});
                return null;
            });
            sleep(Duration.ofSeconds(1));
            screenshot(name + "-import");
            lookAt(new PauseScreen(true), "", name + "-pause");
            onGameThread(() -> {
                minecraft.gui.setScreen(null);
                system.game().openScreenshots();
                return null;
            });
            sleep(Duration.ofSeconds(2));
            screenshot(name + "-gallery");
            // The first cell opens the viewer.
            int scale = onGameThread(() -> minecraft.getWindow().getGuiScale());
            int guiWidth = onGameThread(() -> minecraft.getWindow().getGuiScaledWidth());
            int columns = Math.max(1, (guiWidth - 16) / (96 + 8));
            int left = (guiWidth - (columns * (96 + 8) - 8)) / 2;
            float windowRatio = onGameThread(() -> minecraft.getWindow().getScreenWidth()
                    / (float) minecraft.getWindow().getWidth());
            injector().moveTo((left + 40) * scale * windowRatio, (40 + 25) * scale * windowRatio);
            sleep(Duration.ofMillis(200));
            injector().mouseButton(1, true);
            sleep(Duration.ofMillis(50));
            injector().mouseButton(1, false);
            sleep(Duration.ofSeconds(1));
            screenshot(name + "-viewer");
            onGameThread(() -> {
                minecraft.gui.setScreen(null);
                return null;
            });
        }
        // Back to the window the run started with.
        onGameThread(() -> minecraft.getWindow().platformWindow().setWindowed(64, 64, 1366, 768));
        sleep(Duration.ofMillis(500));
    }

    /**
     * The GUI sizes players' windows give, each a framebuffer and a GUI scale: the smallest the
     * game allows (320x240), a Chromebook's browser tab at the largest scale (683x325), and narrow,
     * short and wide ones between.
     */
    private static final int[][] LAYOUT_SIZES = {
        {640, 480, 2}, {1280, 720, 3}, {1024, 768, 3}, {1366, 650, 2}, {1920, 600, 2}, {1920, 1080, 3},
    };

    /**
     * Every screen's layout judged, not looked at ({@link LayoutCheck}): Incari's screens and the
     * game's that Incari changes, at each of {@link #LAYOUT_SIZES}, in Incari's font and in the
     * game's pixel font (a theme's choice, and what's drawn if the page can't load Incari's). A
     * widget off the screen or over another, or text past its box, fails, and the screen's picture
     * is kept. Every problem is listed in {@code layout.txt}.
     */
    private void layoutScenario() throws Exception {
        waitForTitle();
        incari.framework.ModuleSystem system = onGameThread(incari.client.modules.IncariModules::system);
        onGameThread(
                () -> incari.client.online.IncariOnline.service().wardrobe().saveOutfit("My Practice Server Setup"));
        java.util.Map<String, Callable<Screen>> screens = new java.util.LinkedHashMap<>();
        screens.put("title", TitleScreen::new);
        screens.put(
                "multiplayer",
                () -> new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new TitleScreen()));
        screens.put(
                "edit-server",
                () -> new net.minecraft.client.gui.screens.ManageServerScreen(
                        new TitleScreen(),
                        net.minecraft.network.chat.Component.literal("Edit Server Info"),
                        ok -> {},
                        new ServerData("Test", "ws://127.0.0.1:8801", ServerData.Type.OTHER)));
        screens.put("modules", () -> {
            incari.client.modules.IncariModules.openMenu(new TitleScreen());
            return minecraft.gui.screen();
        });
        screens.put("profiles", () -> {
            incari.client.modules.IncariModules.openProfiles(new TitleScreen());
            return minecraft.gui.screen();
        });
        screens.put("theme", () -> {
            incari.client.modules.IncariModules.openSettings(
                    new TitleScreen(), java.util.Objects.requireNonNull(system.module("theme")));
            return minecraft.gui.screen();
        });
        screens.put("text-entry", () -> {
            system.game().askText("Import theme", "Paste a theme code (Ctrl+V), then press Done.", "", text -> {});
            return minecraft.gui.screen();
        });
        screens.put("settings", () -> new incari.client.settings.IncariSettingsScreen(new TitleScreen()));
        screens.put("recorder", () -> new incari.client.recorder.RecorderScreen(new TitleScreen()));
        screens.put("edit-profile", () -> incari.client.profile.ProfileScreen.from(new TitleScreen()));
        screens.put("wardrobe-outfits", () -> incari.client.cosmetics.WardrobeScreen.showing(new TitleScreen(), null));
        screens.put(
                "wardrobe-emotes",
                () -> incari.client.cosmetics.WardrobeScreen.showing(
                        new TitleScreen(), incari.online.Cosmetic.Slot.EMOTE));
        screens.put(
                "wardrobe-nametags",
                () -> incari.client.cosmetics.WardrobeScreen.showing(
                        new TitleScreen(), incari.online.Cosmetic.Slot.NAMETAG));
        screens.put("credits", () -> new incari.client.credits.CreditsScreen(new TitleScreen()));
        screens.put("chat-menu", () -> new incari.client.online.ChatMenuScreen(new TitleScreen()));
        screens.put("account", () -> new incari.client.online.AccountScreen(new TitleScreen()));
        for (String form : new String[] {
            "SignInScreen",
            "CreateAccountScreen",
            "ForgotPasswordScreen",
            "ChangePasswordScreen",
            "BlockedPlayersScreen"
        }) {
            screens.put(form, () -> {
                var constructor = Class.forName("incari.client.online." + form)
                        .asSubclass(Screen.class)
                        .getDeclaredConstructor(Screen.class);
                constructor.setAccessible(true);
                return constructor.newInstance(new TitleScreen());
            });
        }
        screens.put("release-notes", () -> {
            var constructor = Class.forName("incari.client.online.ReleaseNotesScreen")
                    .asSubclass(Screen.class)
                    .getDeclaredConstructor(Screen.class, String.class, java.util.List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    new TitleScreen(),
                    "0.1.2",
                    java.util.List.of(
                            "Buttons and text fit their boxes on small screens and at browser zoom.",
                            "The default GUI scale is one step smaller, so menus leave room for the game."));
        });
        screens.put("relay", () -> new incari.client.relay.RelayScreen(new TitleScreen()));
        screens.put(
                "relay-connect",
                () -> new incari.client.relay.RelayConnectScreen(new TitleScreen(), "ws://127.0.0.1:9", "ABCD"));
        screens.put(
                "password",
                () -> new incari.client.eagler.PasswordScreen(
                        new TitleScreen(),
                        new ServerData("Smoke", "localhost", ServerData.Type.OTHER),
                        ServerAddress.parseString("localhost"),
                        new incari.client.eagler.EaglerAuth.Request(
                                "localhost",
                                incari.net.eagler.EaglerPassword.PLAINTEXT,
                                "Welcome back to the server. Log in with the password you registered with"
                                        + " last time. If you forgot it, ask a staff member on Discord to reset"
                                        + " it for you.")));

        StringBuilder all = new StringBuilder();
        String[][] fonts = {{"smooth", "red_hat_display.ttf"}, {"pixel", null}};
        for (String[] font : fonts) {
            onGameThread(() -> {
                incari.client.ui.SmoothFont.useFont(font[1], 1.0F);
                return null;
            });
            sleep(Duration.ofSeconds(2));
            for (int[] size : LAYOUT_SIZES) {
                if (!windowSize(size[0], size[1])) {
                    continue;
                }
                guiScale(size[2]);
                String at = onGameThread(() -> minecraft.getWindow().getGuiScaledWidth() + "x"
                        + minecraft.getWindow().getGuiScaledHeight());
                for (var entry : screens.entrySet()) {
                    String name = entry.getKey();
                    java.util.List<String> problems;
                    try {
                        onGameThread(() -> {
                            Screen screen = entry.getValue().call();
                            if (!java.util.Objects.equals(minecraft.gui.screen(), screen)) {
                                minecraft.gui.setScreen(screen);
                            }
                            return null;
                        });
                        // A frame or two for what a screen lays out after it opens (a caption, a fetch).
                        sleep(Duration.ofMillis(300));
                        problems = onGameThread(() -> {
                            Screen shown = minecraft.gui.screen();
                            return shown == null
                                    ? java.util.List.of("no screen open")
                                    : LayoutCheck.problems(minecraft, shown);
                        });
                    } catch (RuntimeException e) {
                        problems = java.util.List.of("couldn't open: " + e);
                    }
                    String label = name + " at " + at + " (" + font[0] + ")";
                    report.check(
                            "layout " + label,
                            problems.isEmpty(),
                            problems.isEmpty()
                                    ? ""
                                    : problems.get(0)
                                            + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : ""));
                    if (!problems.isEmpty()) {
                        screenshot("layout-" + name + "-" + at + "-" + font[0]);
                        all.append(label).append('\n');
                        problems.forEach(
                                problem -> all.append("  ").append(problem).append('\n'));
                    }
                }
            }
        }
        onGameThread(() -> {
            incari.client.ui.SmoothFont.useFont("red_hat_display.ttf", 1.0F);
            minecraft.gui.setScreen(new TitleScreen());
            return null;
        });
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("layout.txt"), all.isEmpty() ? "No problems.\n" : all.toString());
    }

    /**
     * Sizes the window so its framebuffer is {@code width} by {@code height} pixels (what the GUI
     * scale divides: on a display scaled up, the window's own size is smaller), and says whether it
     * took that size.
     */
    private boolean windowSize(int width, int height) {
        float ratio = onGameThread(() ->
                minecraft.getWindow().getWidth() / (float) minecraft.getWindow().getScreenWidth());
        onGameThread(() -> minecraft
                .getWindow()
                .platformWindow()
                .setWindowed(64, 64, Math.round(width / ratio), Math.round(height / ratio)));
        boolean sized = waitUntil(
                Duration.ofSeconds(5),
                () -> onGameThread(() -> Math.abs(minecraft.getWindow().getWidth() - width) <= 2
                        && Math.abs(minecraft.getWindow().getHeight() - height) <= 2));
        report.check(
                "framebuffer " + width + "x" + height,
                sized,
                onGameThread(() -> minecraft.getWindow().getWidth() + "x"
                        + minecraft.getWindow().getHeight()));
        sleep(Duration.ofMillis(500));
        return sized;
    }

    private Screen menu() {
        return onGameThread(() -> {
            incari.client.modules.IncariModules.openMenu(new TitleScreen());
            return minecraft.gui.screen();
        });
    }

    private void guiScale(int scale) {
        onGameThread(() -> {
            minecraft.options.guiScale().set(scale);
            minecraft.resizeGui();
            return null;
        });
        sleep(Duration.ofMillis(300));
    }

    /** The pointer over the settings row labelled {@code label}, at the label's start. */
    private void hoverLabel(String label) {
        float[] at = onGameThread(() -> {
            Screen screen = minecraft.gui.screen();
            if (screen == null) {
                return null;
            }
            for (var child : screen.children()) {
                if (child instanceof incari.client.ui.ControlList list) {
                    for (var row : list.children()) {
                        // The label is the row's own text, not a widget: read it from the row.
                        java.lang.reflect.Field field;
                        try {
                            field = row.getClass().getDeclaredField("label");
                        } catch (NoSuchFieldException e) {
                            continue;
                        }
                        field.setAccessible(true);
                        if (field.get(row) instanceof net.minecraft.network.chat.Component text
                                && text.getString().equals(label)) {
                            var window = minecraft.getWindow();
                            float toWindow = (float) window.getScreenWidth() / window.getGuiScaledWidth();
                            return new float[] {(row.getContentX() + 6) * toWindow, row.getContentYMiddle() * toWindow};
                        }
                    }
                }
            }
            return null;
        });
        report.check("settings row " + label, at != null, "");
        if (at != null) {
            injector().moveTo(at[0], at[1]);
        }
    }

    /**
     * Cosmetics (ADR 0061): worn on the title screen's player, the Wardrobe's tabs, then in a world
     * from in front and behind, an emote turning the camera and turning it back, the emote wheel,
     * and the pause menu's and module menu's ways in. Others' cosmetics come from the online service
     * and are checked by {@code :online}'s tests and smokeOnline.
     */
    private void cosmeticsScenario() throws Exception {
        waitForTitle();
        incari.online.Wardrobe wardrobe =
                onGameThread(() -> incari.client.online.IncariOnline.service().wardrobe());
        onGameThread(() -> {
            wardrobe.wear(incari.online.Loadout.EMPTY);
            wardrobe.wear(cosmetic("cape.aurora"));
            wardrobe.wear(cosmetic("hat.crown"));
            wardrobe.wear(cosmetic("nametag.aurora"));
            minecraft.gui.setScreen(new TitleScreen());
            return null;
        });
        sleep(Duration.ofSeconds(3));
        screenshot("cosmetics-title");
        boolean titleButton = onGameThread(() -> hasButton("Cosmetics"));
        report.check("the title card has Cosmetics", titleButton, screenName());

        for (incari.online.Cosmetic.Slot slot : incari.online.Cosmetic.Slot.values()) {
            onGameThread(() -> {
                minecraft.gui.setScreen(incari.client.cosmetics.WardrobeScreen.showing(new TitleScreen(), slot));
                return null;
            });
            sleep(Duration.ofSeconds(2));
            screenshot("cosmetics-wardrobe-" + slot.prefix());
        }
        onGameThread(() -> {
            wardrobe.saveOutfit("Smoke");
            minecraft.gui.setScreen(incari.client.cosmetics.WardrobeScreen.showing(new TitleScreen(), null));
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("cosmetics-wardrobe-outfits");
        report.check(
                "an outfit is saved",
                onGameThread(
                        () -> wardrobe.outfits().stream().anyMatch(o -> o.name().equals("Smoke"))),
                "");

        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        takeControl();
        serverCommands("time set noon", "weather clear", "execute as @p at @s run tp @s ~ ~ ~ 0 10");
        sleep(Duration.ofSeconds(2));
        onGameThread(() -> {
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("cosmetics-world-front");
        onGameThread(() -> {
            wardrobe.wear(cosmetic("back.angel_wings"));
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("cosmetics-world-back");
        for (String id : new String[] {"back.frost_wings", "back.bat_wings", "back.backpack"}) {
            onGameThread(() -> {
                wardrobe.wear(cosmetic(id));
                return null;
            });
            sleep(Duration.ofSeconds(1));
            screenshot("cosmetics-world-" + id.replace('.', '-'));
        }
        onGameThread(() -> {
            wardrobe.wear(cosmetic("back.angel_wings"));
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
            return null;
        });
        for (String id : new String[] {"hat.halo", "hat.top_hat", "hat.beanie", "hat.horns", "hat.cat_ears"}) {
            onGameThread(() -> {
                wardrobe.wear(cosmetic(id));
                return null;
            });
            sleep(Duration.ofSeconds(1));
            screenshot("cosmetics-world-" + id.replace('.', '-'));
        }
        boolean worn = onGameThread(() -> {
            var state = new net.minecraft.client.renderer.entity.state.AvatarRenderState();
            incari.client.cosmetics.Cosmetics.extract(player(), state);
            return state.incariWorn != incari.client.cosmetics.Worn.NONE;
        });
        report.check("the player's render state carries what's worn", worn, "");

        // An emote: the camera turns to face the player, and back once it's over.
        onGameThread(() -> {
            minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            return null;
        });
        boolean played = onGameThread(() -> incari.client.cosmetics.Cosmetics.playEmote(cosmetic("emote.wave")));
        sleep(Duration.ofMillis(1200));
        screenshot("cosmetics-emote-wave");
        boolean turned = onGameThread(
                () -> minecraft.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        boolean back = waitUntil(
                Duration.ofSeconds(6),
                () -> minecraft.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON);
        report.check(
                "an emote plays and the camera comes back",
                played && turned && back,
                "played " + played + ", turned " + turned + ", back " + back);
        onGameThread(() -> incari.client.cosmetics.Cosmetics.playEmote(cosmetic("emote.cheer")));
        sleep(Duration.ofMillis(300));
        holdKey(26, 'w', Duration.ofMillis(600));
        boolean cut = waitUntil(
                Duration.ofSeconds(2),
                () -> minecraft.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON);
        report.check("moving ends an emote", cut, "");

        // The emote wheel, held open by G.
        injector().key(10, 'g', true);
        boolean wheel = waitUntil(
                Duration.ofSeconds(3),
                () -> minecraft.gui.screen() instanceof incari.client.cosmetics.EmoteWheelScreen);
        sleep(Duration.ofMillis(500));
        screenshot("cosmetics-emote-wheel");
        injector().key(10, 'g', false);
        boolean closed = waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() == null);
        report.check("the emote wheel opens on G and closes on letting go", wheel && closed, screenName());
        // A quick tap, let go before the wheel opens: it waits for a click, and G again closes it.
        injector().key(10, 'g', true);
        injector().key(10, 'g', false);
        boolean tapped = waitUntil(
                Duration.ofSeconds(3),
                () -> minecraft.gui.screen() instanceof incari.client.cosmetics.EmoteWheelScreen);
        sleep(Duration.ofMillis(500));
        boolean stays = minecraft.gui.screen() instanceof incari.client.cosmetics.EmoteWheelScreen;
        injector().key(10, 'g', true);
        injector().key(10, 'g', false);
        boolean tapClosed = waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() == null);
        report.check(
                "a tap of G leaves the emote wheel open for a click, and G again closes it",
                tapped && stays && tapClosed,
                "opened " + tapped + ", stayed " + stays + ", closed " + tapClosed);

        // A trail, walking.
        onGameThread(() -> {
            wardrobe.wear(cosmetic("trail.hearts"));
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            return null;
        });
        injector().key(26, 'w', true);
        sleep(Duration.ofMillis(1500));
        screenshot("cosmetics-trail");
        injector().key(26, 'w', false);

        // The personal effects: the outline, the glint, the hurt tint and a kill's burst.
        incari.framework.ModuleSystem system = onGameThread(incari.client.modules.IncariModules::system);
        incari.framework.Module outline = module(system, "blockoutline");
        incari.framework.Module hitColor = module(system, "hitcolor");
        incari.framework.Module glint = module(system, "glintcolor");
        incari.framework.Module effects = module(system, "hiteffects");
        onGameThread(() -> {
            wardrobe.takeOff(incari.online.Cosmetic.Slot.TRAIL);
            ((incari.framework.setting.BooleanSetting)
                            java.util.Objects.requireNonNull(outline.settings().get("fill")))
                    .set(true);
            outline.setEnabled(true);
            hitColor.setEnabled(true);
            glint.setEnabled(true);
            effects.setEnabled(true);
            minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            return null;
        });
        // A clear, flat patch to stand on: the trail walked somewhere.
        serverCommands(
                "execute as @p at @s run fill ~-4 ~-1 ~-4 ~4 ~-1 ~4 stone",
                "execute as @p at @s run fill ~-4 ~ ~-4 ~4 ~4 ~4 air",
                "item replace entity @p weapon.mainhand with diamond_sword[enchantments={sharpness:5}]",
                "execute as @p at @s run tp @s ~ ~ ~ 0 60");
        sleep(Duration.ofSeconds(2));
        screenshot("cosmetics-outline-glint");
        // Painted, it's Incari's own reloadable texture in the game's slot, not the game's SimpleTexture.
        boolean tinted = onGameThread(() -> minecraft
                .getTextureManager()
                .getTexture(net.minecraft.client.renderer.feature.ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
                .getClass()
                .getSimpleName()
                .equals("TintedGlint"));
        report.check("Enchant Glint paints the glint", tinted, "");
        java.util.concurrent.atomic.AtomicInteger kills = new java.util.concurrent.atomic.AtomicInteger();
        incari.framework.event.KillListener killProbe = kills::incrementAndGet;
        onGameThread(() -> {
            system.events().add(incari.framework.event.Events.KILL, this, killProbe);
            return null;
        });
        serverCommands(
                "execute as @p at @s run tp @s ~ ~ ~ 0 30",
                "execute as @p at @s run summon pig ~ ~ ~2.2 {NoAI:1b,Silent:1b}");
        sleep(Duration.ofSeconds(1));
        injector().mouseButton(1, true);
        sleep(Duration.ofMillis(50));
        injector().mouseButton(1, false);
        sleep(Duration.ofMillis(150));
        screenshot("cosmetics-hit-color");
        serverCommands("effect give @p strength 30 10 true");
        sleep(Duration.ofMillis(800));
        injector().mouseButton(1, true);
        sleep(Duration.ofMillis(50));
        injector().mouseButton(1, false);
        boolean killed = waitUntil(Duration.ofSeconds(3), () -> kills.get() > 0);
        screenshot("cosmetics-kill-effect");
        report.check("a kill fires the kill event", killed, "kills " + kills.get());
        onGameThread(() -> {
            system.events().remove(incari.framework.event.Events.KILL, killProbe);
            outline.setEnabled(false);
            hitColor.setEnabled(false);
            glint.setEnabled(false);
            effects.setEnabled(false);
            return null;
        });
        boolean restored = onGameThread(() -> minecraft
                        .getTextureManager()
                        .getTexture(net.minecraft.client.renderer.feature.ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
                        .getClass()
                == net.minecraft.client.renderer.texture.SimpleTexture.class);
        report.check("the game's glint comes back", restored, "");

        // The ways in from a world: the pause menu and the module menu.
        onGameThread(() -> {
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("cosmetics-pause");
        // ADR 0062: the pause menu's one Incari button opens Incari Settings, where the Wardrobe is a tile.
        report.check(
                "the pause menu has Incari Settings", onGameThread(() -> hasButton("Incari Settings")), screenName());
        onGameThread(() -> {
            minecraft.gui.setScreen(new incari.client.settings.IncariSettingsScreen(minecraft.gui.screen()));
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("cosmetics-incari-settings");
        report.check("Incari Settings has Cosmetics", onGameThread(() -> hasButton("Cosmetics")), screenName());
        onGameThread(() -> {
            incari.client.modules.IncariModules.openMenu(null);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("cosmetics-module-menu");
        report.check("the module menu has Cosmetics", onGameThread(() -> hasButton("Cosmetics")), screenName());

        // Others' cosmetics off is kept.
        onGameThread(() -> {
            wardrobe.setShowOthers(false);
            minecraft.gui.setScreen(
                    incari.client.cosmetics.WardrobeScreen.showing(null, incari.online.Cosmetic.Slot.CAPE));
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("cosmetics-others-off");
        onGameThread(() -> {
            wardrobe.setShowOthers(true);
            minecraft.gui.setScreen(null);
            return null;
        });
    }

    private static incari.online.Cosmetic cosmetic(String id) {
        return java.util.Objects.requireNonNull(incari.online.Catalog.byId(id), id);
    }

    /** Whether the shown screen has a widget saying {@code label}. */
    private boolean hasButton(String label) {
        return minecraft.gui.screen() != null && hasButton(minecraft.gui.screen(), label);
    }

    /** Searches lists' rows too: Incari Settings' tiles sit in its list, not on the screen. */
    private static boolean hasButton(
            net.minecraft.client.gui.components.events.ContainerEventHandler parent, String label) {
        for (var child : parent.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.getMessage().getString().equals(label)) {
                return true;
            }
            if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container
                    && hasButton(container, label)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ADR 0054 and 0062: Shulker Tooltips over a filled box in the inventory, the screenshot gallery
     * (F2 twice, the event, the gallery and the viewer), Incari Settings from the pause menu, and
     * the Theme Editor's presets and fonts applied and Frost given back.
     */
    private void screenModulesScenario() throws Exception {
        waitForTitle();
        incari.framework.ModuleSystem system = onGameThread(incari.client.modules.IncariModules::system);
        incari.framework.Module theme = module(system, "theme");
        incari.framework.Module shulker = module(system, "shulkertooltips");
        onGameThread(() -> {
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("screens-title");

        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        takeControl();
        serverCommands(
                "time set noon",
                "item replace entity @p hotbar.0 with purple_shulker_box[container=["
                        + "{slot:0,item:{id:\"diamond\",count:12}},{slot:1,item:{id:\"golden_apple\",count:3}},"
                        + "{slot:4,item:{id:\"diamond_sword\",count:1}},{slot:13,item:{id:\"ender_pearl\",count:16}},"
                        + "{slot:26,item:{id:\"totem_of_undying\",count:1}}]]");
        sleep(Duration.ofSeconds(1));

        // Shulker Tooltips: the box's tooltip carries the grid while on, and not while off.
        boolean gameTooltip = onGameThread(
                () -> player().getInventory().getItem(0).getTooltipImage().isEmpty());
        onGameThread(() -> {
            shulker.setEnabled(true);
            return null;
        });
        boolean preview = onGameThread(
                () -> player().getInventory().getItem(0).getTooltipImage().orElse(null)
                        instanceof incari.client.modules.ModuleTooltips.Preview);
        report.check(
                "shulker tooltips show the grid",
                gameTooltip && preview,
                "game none " + gameTooltip + ", preview " + preview);
        injector().tap(8, 'e');
        boolean open = waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof InventoryScreen);
        if (open) {
            int scale = onGameThread(() -> minecraft.getWindow().getGuiScale());
            int guiWidth = onGameThread(() -> minecraft.getWindow().getGuiScaledWidth());
            int guiHeight = onGameThread(() -> minecraft.getWindow().getGuiScaledHeight());
            // Window units, which a HiDPI window's framebuffer is larger than.
            float ratio = onGameThread(() -> minecraft.getWindow().getScreenWidth()
                    / (float) minecraft.getWindow().getWidth());
            float x = ((guiWidth - 176) / 2 + 8 + 8) * scale * ratio;
            float y = ((guiHeight - 166) / 2 + 142 + 8) * scale * ratio;
            injector().moveTo(x, y);
            sleep(Duration.ofMillis(300));
            injector().moveTo(x + 1, y);
            sleep(Duration.ofMillis(500));
            String mouse = onGameThread(() -> minecraft.mouseHandler.xpos() + "," + minecraft.mouseHandler.ypos()
                    + " grabbed " + minecraft.mouseHandler.isMouseGrabbed() + " window "
                    + minecraft.getWindow().getScreenWidth() + "x"
                    + minecraft.getWindow().getScreenHeight());
            report.check("pointer over the box", true, "at " + x + "," + y + ", game sees " + mouse);
            screenshot("screens-shulker-tooltip");
            injector().tap(41, 0x1B);
            waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() == null);
        }

        // Screenshot Manager: F2 twice, heard by modules, then the gallery.
        java.util.List<String> taken = new java.util.concurrent.CopyOnWriteArrayList<>();
        incari.framework.event.ScreenshotListener probe = taken::add;
        onGameThread(() -> {
            system.events().add(incari.framework.event.Events.SCREENSHOT, this, probe);
            return null;
        });
        injector().tap(59, 0x4000003B);
        sleep(Duration.ofMillis(1200));
        serverCommands("execute as @p at @s run tp @s ~ ~ ~ 90 0");
        sleep(Duration.ofMillis(800));
        injector().tap(59, 0x4000003B);
        boolean heard = waitUntil(Duration.ofSeconds(5), () -> taken.size() >= 2);
        report.check("screenshots heard by modules", heard, taken.toString());
        onGameThread(() -> {
            system.events().remove(incari.framework.event.Events.SCREENSHOT, probe);
            system.game().openScreenshots();
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("screens-gallery");
        report.check(
                "the gallery opens",
                onGameThread(() -> minecraft.gui.screen() != null
                        && minecraft.gui.screen().getTitle().getString().equals("Screenshots")),
                screenName());
        // The newest first: the first cell opens the viewer.
        int scale = onGameThread(() -> minecraft.getWindow().getGuiScale());
        int guiWidth = onGameThread(() -> minecraft.getWindow().getGuiScaledWidth());
        int columns = Math.max(1, (guiWidth - 16) / (96 + 8));
        int left = (guiWidth - (columns * (96 + 8) - 8)) / 2;
        float windowRatio = onGameThread(() -> minecraft.getWindow().getScreenWidth()
                / (float) minecraft.getWindow().getWidth());
        injector().moveTo((left + 40) * scale * windowRatio, (40 + 25) * scale * windowRatio);
        sleep(Duration.ofMillis(200));
        injector().mouseButton(1, true);
        sleep(Duration.ofMillis(50));
        injector().mouseButton(1, false);
        sleep(Duration.ofSeconds(1));
        screenshot("screens-viewer");
        onGameThread(() -> {
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("screens-pause");
        boolean pauseButton = onGameThread(() -> minecraft.gui.screen() != null
                && minecraft.gui.screen().children().stream()
                        .anyMatch(child -> child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                                && widget.getMessage().getString().equals("Incari Settings")));
        report.check("pause menu has Incari Settings", pauseButton, "");
        onGameThread(() -> {
            minecraft.gui.setScreen(new incari.client.settings.IncariSettingsScreen(minecraft.gui.screen()));
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("screens-incari-settings");

        // Theme Editor: always on; presets and fonts live, then Frost back.
        // Frost's accent: an earlier run's profile may start the theme elsewhere.
        int frostAccent = 0xFFA9DCFF;
        report.check("the theme editor is always on", onGameThread(theme::isEnabled), "");
        choose(theme, "preset", "ember");
        sleep(Duration.ofMillis(500));
        screenshot("screens-theme-ember-pause");
        int emberAccent = onGameThread(() -> incari.client.ui.Theme.ACCENT);
        choose(theme, "preset", "mint");
        onGameThread(() -> {
            incari.client.modules.IncariModules.openMenu(null);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("screens-theme-mint-menu");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });
        sleep(Duration.ofMillis(500));
        screenshot("screens-theme-mint-hud");
        int mintAccent = onGameThread(() -> incari.client.ui.Theme.ACCENT);
        for (String font : new String[] {"inter", "minecraft", "jetbrains_mono"}) {
            choose(theme, "font", font);
            onGameThread(() -> {
                minecraft.gui.setScreen(new incari.client.settings.IncariSettingsScreen(null));
                return null;
            });
            sleep(Duration.ofSeconds(2));
            screenshot("screens-font-" + font);
        }
        choose(theme, "font", "red_hat_display");
        choose(theme, "preset", "frost");
        sleep(Duration.ofMillis(500));
        int after = onGameThread(() -> incari.client.ui.Theme.ACCENT);
        report.check(
                "themes apply and Frost comes back",
                emberAccent == 0xFFFFB067 && mintAccent == 0xFF7FE8C3 && after == frostAccent,
                Integer.toHexString(emberAccent) + " " + Integer.toHexString(mintAccent) + " "
                        + Integer.toHexString(after));
        onGameThread(() -> {
            shulker.setEnabled(false);
            return null;
        });
        sleep(Duration.ofMillis(500));
        system.flush();
    }

    /** Sets {@code module}'s choice {@code id} to the choice saved as {@code key}. */
    private void choose(incari.framework.Module module, String id, String key) {
        incari.framework.setting.Setting<?> setting = module.settings().get(id);
        if (!(setting instanceof incari.framework.setting.ChoiceSetting<?> choice)) {
            throw new IllegalStateException(module.id() + " has no choice " + id);
        }
        onGameThread(() -> {
            chooseKey(choice, key);
            return null;
        });
    }

    private static <E extends Enum<E>> void chooseKey(incari.framework.setting.ChoiceSetting<E> setting, String key) {
        E value = setting.fromKey(key);
        if (value == null) {
            throw new IllegalStateException(setting.id() + " has no choice " + key);
        }
        setting.set(value);
    }

    private incari.framework.Module module(incari.framework.ModuleSystem system, String id) {
        incari.framework.Module module = system.module(id);
        if (module == null) {
            throw new IllegalStateException("No module " + id);
        }
        return module;
    }

    /**
     * ADR 0051: the minimap and waypoints in a world. Four waypoints round the player (one ahead,
     * one ahead and to the left, one far beyond the render distance, one behind) and one in the
     * Nether; screenshots of the map round and turning, square and north up, zoomed, the list, the
     * editor and the HUD editor; a death leaves a waypoint; the world's waypoints are saved.
     */
    private void waypointsScenario() throws Exception {
        waitForTitle();
        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        takeControl();
        serverCommands("time set noon", "weather clear");
        incari.framework.ModuleSystem system = onGameThread(incari.client.modules.IncariModules::system);
        incari.framework.Module minimap = system.module("minimap");
        incari.framework.Module waypointsModule = system.module("waypoints");
        report.check("both modules", minimap != null && waypointsModule != null, "");
        if (minimap == null || waypointsModule == null) {
            return;
        }
        incari.framework.waypoint.Waypoints waypoints = system.waypoints();
        report.check(
                "the world's waypoints are open",
                onGameThread(
                        () -> waypoints.world() != null && waypoints.world().startsWith("world/")),
                String.valueOf(onGameThread(waypoints::world)));
        BlockPos at = onGameThread(() -> player().blockPosition());
        String overworld = incari.framework.game.PlayerView.OVERWORLD;
        onGameThread(() -> {
            waypoints.add(new incari.framework.waypoint.Waypoint(
                    "Base",
                    at.getX() + 4,
                    at.getY(),
                    at.getZ() - 28,
                    overworld,
                    0x5B8CFF,
                    incari.framework.hud.Shape.STAR));
            waypoints.add(new incari.framework.waypoint.Waypoint(
                    "Mine",
                    at.getX() - 40,
                    at.getY() - 6,
                    at.getZ() - 55,
                    overworld,
                    0xFF9F43,
                    incari.framework.hud.Shape.DIAMOND));
            waypoints.add(new incari.framework.waypoint.Waypoint(
                    "Far tower",
                    at.getX() + 60,
                    at.getY() + 20,
                    at.getZ() - 600,
                    overworld,
                    0x8BE04E,
                    incari.framework.hud.Shape.TRIANGLE));
            waypoints.add(new incari.framework.waypoint.Waypoint(
                    "Home",
                    at.getX() - 6,
                    at.getY(),
                    at.getZ() + 30,
                    overworld,
                    0xF06FD8,
                    incari.framework.hud.Shape.HEART));
            waypoints.add(new incari.framework.waypoint.Waypoint(
                    "Portal",
                    10,
                    70,
                    10,
                    incari.framework.game.PlayerView.NETHER,
                    0x9D7BFF,
                    incari.framework.hud.Shape.HEXAGON));
            minimap.setEnabled(true);
            waypointsModule.setEnabled(true);
            player().setYRot(180.0F); // north
            player().setXRot(-8.0F);
            return null;
        });
        sleep(Duration.ofSeconds(4));
        screenshot("waypoints-north");

        onGameThread(() -> {
            player().setYRot(270.0F); // east
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("waypoints-east");

        // Square, north up, zoomed in, and zoomed all the way out.
        incari.framework.setting.Setting<?> shape = minimap.settings().get("shape");
        incari.framework.setting.Setting<?> turn = minimap.settings().get("turn");
        incari.framework.setting.Setting<?> zoom = minimap.settings().get("zoom");
        onGameThread(() -> {
            ((incari.framework.setting.ChoiceSetting<?>) java.util.Objects.requireNonNull(shape)).reset();
            next((incari.framework.setting.ChoiceSetting<?>) shape);
            ((incari.framework.setting.BooleanSetting) java.util.Objects.requireNonNull(turn)).set(false);
            ((incari.framework.setting.IntSetting) java.util.Objects.requireNonNull(zoom)).set(6);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("waypoints-square-north-up-zoomed-in");
        onGameThread(() -> {
            ((incari.framework.setting.IntSetting) java.util.Objects.requireNonNull(zoom)).set(1);
            return null;
        });
        sleep(Duration.ofSeconds(3));
        screenshot("waypoints-square-zoomed-out");
        onGameThread(() -> {
            minimap.settings().resetAll();
            minimap.hudElements().get(0).scale().set(1.5F);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("waypoints-round-1.5x");

        onGameThread(() -> {
            system.game().openWaypoints();
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("waypoints-list");
        onGameThread(() -> {
            incari.framework.waypoint.Waypoint base = waypoints.all().get(0);
            system.game().editWaypoint(base, false);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("waypoints-edit");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });
        // The New waypoint key: the editor, on a new waypoint where the player stands.
        injector().key(17, 'n', true);
        sleep(Duration.ofMillis(150));
        injector().key(17, 'n', false);
        boolean editor = waitUntil(
                Duration.ofSeconds(3),
                () -> minecraft.gui.screen() != null
                        && minecraft.gui.screen().getClass().getSimpleName().equals("WaypointEditScreen"));
        report.check(
                "the new waypoint key opens the editor",
                editor,
                String.valueOf(onGameThread(() -> minecraft.gui.screen() == null
                        ? "no screen"
                        : minecraft.gui.screen().getClass().getSimpleName())));
        sleep(Duration.ofMillis(500));
        screenshot("waypoints-new");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });

        onGameThread(() -> {
            incari.client.modules.IncariModules.openHudEditor(null);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("waypoints-hudeditor");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            minimap.hudElements().get(0).resetPlacement();
            return null;
        });

        // A death leaves a waypoint where it happened.
        int before = onGameThread(() -> waypoints.all().size());
        serverCommands("kill @p");
        boolean marked = waitUntil(Duration.ofSeconds(5), () -> waypoints.death() != null);
        report.check(
                "a death is marked",
                marked && onGameThread(() -> waypoints.all().size()) == before + 1,
                "");
        onGameThread(() -> {
            player().respawn();
            return null;
        });
        waitUntil(
                Duration.ofSeconds(10), () -> inWorld() && !player().isDeadOrDying() && minecraft.gui.screen() == null);
        sleep(Duration.ofSeconds(2));
        onGameThread(() -> {
            player().setYRot(180.0F);
            player().setXRot(-8.0F);
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("waypoints-after-death");

        onGameThread(() -> {
            system.flush();
            return null;
        });
        java.nio.file.Path folder = minecraft.gameDirectory.toPath().resolve("incari/modules/waypoints");
        String world = String.valueOf(onGameThread(waypoints::world)).replace('/', '_');
        long files;
        try (var list = java.nio.file.Files.list(folder)) {
            files = list.filter(file -> file.getFileName().toString().startsWith(world))
                    .count();
        } catch (java.io.IOException e) {
            files = 0;
        }
        report.check("the world's waypoints are saved", files == 1, folder + ": " + files + " files for " + world);

        // On green land, for the map's colors: the nearest plains, found as the locate command does.
        BlockPos plains = onGameThread(() -> {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            return server.submit(() -> {
                        var found = server.overworld()
                                .findClosestBiome3d(
                                        biome -> biome.is(net.minecraft.world.level.biome.Biomes.PLAINS),
                                        at,
                                        6400,
                                        32,
                                        64);
                        return found == null ? null : found.getFirst();
                    })
                    .get(30, TimeUnit.SECONDS);
        });
        if (plains != null) {
            serverCommands("gamemode creative @p", "tp @p " + plains.getX() + " 140 " + plains.getZ() + " 0 50");
            onGameThread(() -> {
                player().getAbilities().flying = true;
                minimap.hudElements().get(0).scale().set(1.5F);
                return null;
            });
            sleep(Duration.ofSeconds(8));
            screenshot("waypoints-plains");
        }

        // The world map: M opens it on the player; zoomed out it shows the spawn too, remembered.
        injector().key(16, 'm', true);
        sleep(Duration.ofMillis(150));
        injector().key(16, 'm', false);
        boolean opened = waitUntil(
                Duration.ofSeconds(3),
                () -> minecraft.gui.screen() != null
                        && minecraft.gui.screen().getClass().getSimpleName().equals("WorldMapScreen"));
        report.check("the world map key opens it", opened, "");
        sleep(Duration.ofSeconds(2));
        screenshot("worldmap");
        onGameThread(() -> {
            var screen = java.util.Objects.requireNonNull(minecraft.gui.screen());
            screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0, -10);
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("worldmap-zoomed-out");
        injector().key(16, 'm', true);
        sleep(Duration.ofMillis(150));
        injector().key(16, 'm', false);
        report.check(
                "the world map key closes it",
                waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() == null),
                "");
        onGameThread(() -> {
            incari.client.modules.MapMemory.save();
            return null;
        });
        java.nio.file.Path maps = minecraft.gameDirectory.toPath().resolve("incari/map");
        long regionFiles;
        try (var walk = java.nio.file.Files.walk(maps)) {
            regionFiles = walk.filter(file -> file.toString().contains(world)
                            && file.getFileName().toString().endsWith(".map"))
                    .count();
        } catch (java.io.IOException e) {
            regionFiles = 0;
        }
        report.check("the explored map is remembered", regionFiles >= 2, regionFiles + " region files");
    }

    private static <E extends Enum<E>> void next(incari.framework.setting.ChoiceSetting<E> setting) {
        setting.next();
    }

    /** Runs commands as the integrated server's console, in order, and waits for them. */
    private void serverCommands(String... commands) {
        onGameThread(() -> {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server == null) {
                throw new IllegalStateException("No integrated server");
            }
            server.submit(() -> {
                        for (String command : commands) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                        }
                    })
                    .get(30, TimeUnit.SECONDS);
            return null;
        });
    }

    /**
     * The HUD editor. Opened from the module menu's Edit HUD button with Toggle Sprint on, in
     * both GUI presets: its line is shown (a sample, nothing being latched) over the blurred world,
     * dragged to the right where it snaps and takes the right side's anchor, resized by its corner,
     * and saved. In Potato the blurred backdrop is drawn about ten times a second while the editor
     * runs at the full frame rate.
     */
    private void hudEditorScenario() throws Exception {
        waitForTitle();
        newSurvivalWorld();
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check("singleplayer world", loaded, loaded ? "" : stateDescription());
        if (!loaded) {
            screenshot("fail-world");
            return;
        }
        takeControl();
        incari.framework.Module toggleSprint = onGameThread(() -> {
            incari.framework.Module module =
                    incari.client.modules.IncariModules.system().module("togglesprint");
            if (module == null) {
                throw new IllegalStateException("No togglesprint module");
            }
            module.setEnabled(true);
            return module;
        });
        incari.framework.hud.HudElement status = toggleSprint.hudElements().get(0);
        for (incari.client.ui.GuiPreset preset : incari.client.ui.GuiPreset.values()) {
            String name = "hudeditor-" + preset.name().toLowerCase(java.util.Locale.ROOT);
            onGameThread(() -> {
                incari.client.ui.Look.set(preset);
                status.resetPlacement();
                minecraft.gui.setScreen(null);
                minecraft.mouseHandler.grabMouse();
                return null;
            });
            sleep(Duration.ofMillis(500));
            injector().tap(229, 0x400000E5); // Right Shift: the module menu
            waitUntil(Duration.ofSeconds(3), () -> screenName().equals("ModuleListScreen"));
            sleep(Duration.ofMillis(500));
            clickButton("Edit HUD");
            boolean open = waitUntil(Duration.ofSeconds(3), () -> screenName().equals("HudEditorScreen"));
            report.check(name + ": Edit HUD opens the editor", open, open ? "" : stateDescription());
            if (!open) {
                screenshot("fail-" + name);
                continue;
            }
            sleep(Duration.ofSeconds(1));
            injector().moveTo(400, 400);
            sleep(Duration.ofMillis(300));
            screenshot(name);

            // Frame rate and backdrop refreshes over two seconds.
            int refreshesBefore = incari.client.gui.BackdropCache.refreshes();
            long framesBefore = onGameThread(() -> (long) minecraft.getFps());
            sleep(Duration.ofSeconds(2));
            int refreshes = incari.client.gui.BackdropCache.refreshes() - refreshesBefore;
            long fps = onGameThread(() -> (long) minecraft.getFps());
            if (preset == incari.client.ui.GuiPreset.POTATO) {
                boolean throttled = refreshes >= 10 && refreshes <= 26;
                report.check(
                        name + ": backdrop ~10 a second",
                        throttled,
                        refreshes + " refreshes in 2 s at " + fps + " fps (" + framesBefore + " before)");
            } else {
                report.check(name + ": backdrop live in Normal", refreshes == 0, refreshes + " refreshes");
            }

            // Drag the line from the top left to the right edge, halfway down.
            var window = onGameThread(minecraft::getWindow);
            float toWindow = (float) window.getScreenWidth() / window.getGuiScaledWidth();
            int[] bounds = new int[4];
            int guiWidth = window.getGuiScaledWidth();
            int guiHeight = window.getGuiScaledHeight();
            float startX = (2 + 10) * toWindow;
            float startY = (2 + 4) * toWindow;
            injector().moveTo(startX, startY);
            sleep(Duration.ofMillis(200));
            injector().mouseButton(1, true);
            sleep(Duration.ofMillis(100));
            float endX = (guiWidth - 30) * toWindow;
            float endY = (guiHeight / 2.0F) * toWindow;
            for (int step = 1; step <= 10; step++) {
                injector().moveTo(startX + (endX - startX) * step / 10, startY + (endY - startY) * step / 10);
                sleep(Duration.ofMillis(40));
            }
            sleep(Duration.ofMillis(300));
            screenshot(name + "-drag");
            injector().mouseButton(1, false);
            sleep(Duration.ofMillis(300));
            String placed = onGameThread(() -> status.anchor().get() + " "
                    + status.offsetX().get() + "," + status.offsetY().get());
            boolean right = placed.startsWith("RIGHT ") && placed.contains(" 2,");
            report.check(name + ": dragged to the right edge, snapped", right, placed);

            // Its corner handle, dragged outward, makes it bigger.
            float before = status.scale().get();
            int[] rect = onGameThread(() -> {
                int width = Math.round(minecraft.font.width("[Sprinting (Toggled)]")
                        * status.scale().get());
                int height =
                        Math.round(minecraft.font.lineHeight * status.scale().get());
                return new int[] {
                    status.anchor().get().x(status.offsetX().get(), width, guiWidth),
                    status.anchor().get().y(status.offsetY().get(), height, guiHeight),
                    width,
                    height
                };
            });
            System.arraycopy(rect, 0, bounds, 0, 4);
            float handleX = (bounds[0] + bounds[2] + 2) * toWindow;
            float handleY = (bounds[1] + bounds[3] + 2) * toWindow;
            injector().moveTo(handleX, handleY);
            sleep(Duration.ofMillis(200));
            injector().mouseButton(1, true);
            sleep(Duration.ofMillis(100));
            injector().moveTo(handleX + 40 * toWindow, handleY + 20 * toWindow);
            sleep(Duration.ofMillis(100));
            injector().moveTo(handleX + 60 * toWindow, handleY + 30 * toWindow);
            sleep(Duration.ofMillis(300));
            screenshot(name + "-resize");
            injector().mouseButton(1, false);
            sleep(Duration.ofMillis(200));
            float after = status.scale().get();
            report.check(name + ": the corner resizes", after > before, before + " -> " + after);
            injector().moveTo(guiWidth / 2.0F * toWindow, guiHeight * 0.7F * toWindow);
            sleep(Duration.ofMillis(300));
            screenshot(name + "-chosen");

            clickButton("gui.done");
            boolean back = waitUntil(Duration.ofSeconds(3), () -> screenName().equals("ModuleListScreen"));
            report.check(name + ": Done goes back to the module menu", back, stateDescription());
        }
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });
        sleep(Duration.ofMillis(1500));
        screenshot("hudeditor-hud");
        Path profile = directory.resolveSibling("incari/modules/profiles/Default.json");
        String saved = Files.isRegularFile(profile) ? Files.readString(profile) : "";
        boolean kept = saved.contains("\"anchor\": \"right\"") || saved.contains("\"anchor\": \"RIGHT\"");
        report.check("placement saved", kept, kept ? profile.toString() : saved);
        onGameThread(() -> {
            incari.client.ui.Look.set(incari.client.ui.GuiPreset.POTATO);
            minecraft.gui.setScreen(new PauseScreen(true));
            return null;
        });
        sleep(Duration.ofMillis(500));
        clickButton("menu.returnToMenu");
        waitUntil(
                Duration.ofMinutes(1), () -> minecraft.level == null && minecraft.gui.screen() instanceof TitleScreen);
    }

    private String screenName() {
        return onGameThread(() -> minecraft.gui.screen() == null
                ? "none"
                : minecraft.gui.screen().getClass().getSimpleName());
    }

    /**
     * Checks Incari's mesher against the game's section compiler: with the game run with
     * {@code -Dincari.mesher=verify}, every section is meshed both ways and compared. Visits
     * terrain of many kinds, every dimension, and the debug world (every block state), with smooth
     * lighting and cutout leaves on and off, and fails if any section's mesh differs.
     */
    private void mesherScenario() throws Exception {
        waitForTitle();
        report.check(
                "mesher in verify mode",
                incari.client.render.mesher.Meshing.mode() == incari.client.render.mesher.Meshing.Mode.VERIFY,
                "run with -Dincari.mesher=verify");
        onGameThread(() -> {
            minecraft.options.renderDistance().set(10);
            minecraft.options.ambientOcclusion().set(true);
            minecraft.options.cutoutLeaves().set(true);
            return null;
        });
        createWorld("incari-mesher-", WorldPresets::createNormalWorldDimensions);
        if (!waitForWorld("normal world")) {
            return;
        }
        for (String setup :
                new String[] {"gamemode spectator", "gamerule advance_time false", "time set 6000", "weather clear"}) {
            serverCommand(setup);
        }
        int[][] places = {
            {0, 120, 0, 0, 30}, {900, 110, 40, 90, 25}, {-1300, 100, 700, 180, 35}, {2600, 130, -2100, -90, 20},
            {-3900, 90, -3300, 45, 40}, {5200, 140, 4100, 135, 30}, {300, 40, -500, 0, 10}, {-800, -20, -800, 90, 5},
            {7000, 100, -6000, -45, 45}, {-9000, 120, 9000, 0, 30},
        };
        for (int[] place : places) {
            serverCommand("tp @s " + place[0] + " " + place[1] + " " + place[2] + " " + place[3] + " " + place[4]);
            settle("overworld " + place[0] + " " + place[2]);
        }
        serverCommand("execute in minecraft:the_nether run tp @s 0 70 0 0 20");
        settle("the nether");
        serverCommand("execute in minecraft:the_nether run tp @s 600 60 -400 90 20");
        settle("the nether, farther");
        serverCommand("execute in minecraft:the_end run tp @s 0 90 0 0 30");
        settle("the end");
        serverCommand("execute in minecraft:the_end run tp @s 1000 70 1000 0 30");
        settle("the end's islands");
        serverCommand("execute in minecraft:overworld run tp @s 0 120 0 0 30");
        settle("overworld again");
        setOption(
                "smooth lighting off",
                () -> minecraft.options.ambientOcclusion().set(false));
        setOption("cutout leaves off", () -> minecraft.options.cutoutLeaves().set(false));
        setOption(
                "smooth lighting on, cutout leaves off",
                () -> minecraft.options.ambientOcclusion().set(true));
        onGameThread(() -> {
            minecraft.options.cutoutLeaves().set(true);
            minecraft.disconnectWithSavingScreen();
            return null;
        });
        waitUntil(Duration.ofMinutes(1), () -> minecraft.level == null);

        createWorld(
                "incari-mesher-debug-",
                registries -> registries
                        .lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.DEBUG)
                        .value()
                        .createWorldDimensions());
        if (waitForWorld("debug world")) {
            serverCommand("gamemode spectator");
            for (int x = 0; x < 360; x += 60) {
                for (int z = 0; z < 360; z += 60) {
                    serverCommand("tp @s " + x + " 90 " + z + " 45 60");
                    settle("debug world " + x + " " + z);
                }
            }
            setOption(
                    "debug world, smooth lighting off",
                    () -> minecraft.options.ambientOcclusion().set(false));
        }
        int[] counts = incari.client.render.mesher.Meshing.verifyCounts();
        report.check(
                "Incari's mesher matches the game's",
                counts[0] > 2000 && counts[1] == 0,
                counts[0] + " sections compared, " + counts[1] + " differ (the log has the first ones)");
        onGameThread(() -> {
            minecraft.options.ambientOcclusion().set(true);
            minecraft.disconnectWithSavingScreen();
            return null;
        });
        waitUntil(Duration.ofMinutes(1), () -> minecraft.level == null);
    }

    private void createWorld(
            String prefix,
            java.util.function.Function<
                            net.minecraft.core.HolderLookup.Provider,
                            net.minecraft.world.level.levelgen.WorldDimensions>
                    dimensions) {
        onGameThread(() -> {
            String name = prefix + System.currentTimeMillis();
            LevelSettings settings = new LevelSettings(
                    name,
                    GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                    true,
                    WorldDataConfiguration.DEFAULT);
            minecraft
                    .createWorldOpenFlows()
                    .createFreshLevel(
                            name, settings, new WorldOptions(SEED, true, false), dimensions, new TitleScreen());
            return null;
        });
    }

    private boolean waitForWorld(String what) {
        boolean loaded = waitUntil(Duration.ofMinutes(3), () -> inWorld() && player().tickCount > 60);
        report.check(what, loaded, loaded ? "created and loaded" : stateDescription());
        return loaded;
    }

    /** Runs a command as the player, through the connection (the world allows commands). */
    private void serverCommand(String command) {
        onGameThread(() -> {
            player().connection.sendCommand(command);
            return null;
        });
        sleep(Duration.ofMillis(300));
    }

    /** Waits for the terrain around to be meshed, then a little longer. */
    private void settle(String where) {
        sleep(Duration.ofSeconds(3));
        boolean done = waitUntil(Duration.ofSeconds(90), () -> minecraft.levelRenderer.hasRenderedAllSections());
        sleep(Duration.ofSeconds(2));
        int[] counts = incari.client.render.mesher.Meshing.verifyCounts();
        org.slf4j.LoggerFactory.getLogger("Smoke test")
                .info(
                        "Mesher at {}: {} sections compared, {} differ{}",
                        where,
                        counts[0],
                        counts[1],
                        done ? "" : " (still meshing)");
        screenshot("mesher-" + where.replace(' ', '-').replace(",", ""));
    }

    private void setOption(String what, Runnable change) {
        // The options recompile every section themselves when they change.
        onGameThread(() -> {
            change.run();
            return null;
        });
        settle(what);
    }

    private void joinScenario(String address) throws Exception {
        waitForTitle();
        onGameThread(() -> {
            ConnectScreen.startConnecting(
                    new TitleScreen(),
                    minecraft,
                    ServerAddress.parseString(address),
                    new ServerData("Incari smoke host", address, ServerData.Type.OTHER),
                    false,
                    null);
            return null;
        });
        boolean joined = waitUntil(Duration.ofMinutes(2), () -> inWorld() && player().tickCount > 40);
        report.check(
                "join over the platform transport",
                joined,
                joined ? "logged in to " + address + " and received the world" : stateDescription());
        if (!joined) {
            screenshot("fail-join");
        }
        if (!inWorld()) {
            return;
        }
        takeControl();
        screenshot("join-world");
        walk();
        chat("hello from the second client");
        sleep(Duration.ofSeconds(3));
        injector().tap(41, 0x1B); // Escape
        waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof PauseScreen);
        clickButton("menu.disconnect");
        report.check(
                "disconnect",
                waitUntil(Duration.ofMinutes(1), () -> minecraft.level == null),
                "left the server cleanly");
    }

    /**
     * Joins a dedicated server: over a WebSocket when the address is a {@code ws://} URL.
     * Changes dimension there and back, disconnects, and reconnects. The player must be an
     * operator on the server, for the teleport commands.
     */
    private void serverScenario(String address) throws Exception {
        waitForTitle();
        ping(address);
        for (int attempt = 1; attempt <= 2; attempt++) {
            String label = attempt == 1 ? "join " : "reconnect to ";
            connect(address);
            boolean joined = waitUntil(Duration.ofMinutes(2), () -> inWorld() && player().tickCount > 40);
            report.check(
                    label + address,
                    joined,
                    joined
                            ? "logged in and received the world over " + transportDescription() + " as "
                                    + versionDescription()
                            : stateDescription());
            if (!joined) {
                screenshot("fail-server-join-" + attempt);
                return;
            }
            boolean translated = viaInPipeline();
            if (attempt == 1) {
                takeControl();
                screenshot("server-world");
                chat("hello over " + (address.startsWith("ws") ? "a WebSocket" : "TCP"));
                if (translated) {
                    // An older server: moving, the inventory, and staying connected while idle
                    // (1.8 wants a movement packet every tick, which Via sends for the game).
                    walk();
                    jump();
                    inventory();
                    stayConnected(Duration.ofSeconds(20));
                } else {
                    // The teleports land inside rock or water; a creative player takes no damage.
                    command("gamemode creative");
                    changeDimension("minecraft:the_nether");
                    changeDimension("minecraft:overworld");
                    transfer(address);
                }
            } else {
                injector().focus();
                sleep(Duration.ofSeconds(2));
            }
            injector().tap(41, 0x1B); // Escape
            waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof PauseScreen);
            clickButton("menu.disconnect");
            report.check(
                    "disconnect (" + attempt + ")",
                    waitUntil(
                            Duration.ofMinutes(1), () -> minecraft.level == null && minecraft.getConnection() == null),
                    "left the server cleanly");
            sleep(Duration.ofSeconds(2));
        }
    }

    /**
     * Skins both ways: this player sets a skin generated from its name (and, as
     * IncariSkinA, a slim model and a cape), joins, and waits for {@code peer}, running the same
     * scenario, to appear with the skin it sent, as a texture of exactly those pixels.
     */
    private void skinsScenario(String peer, String address) throws Exception {
        waitForTitle();
        String self = minecraft.getUser().getName();
        writeGeneratedSkin(self, self.endsWith("A"));
        connect(address);
        waitUntil(Duration.ofMinutes(2), this::inWorld);
        respawnIfDead();
        boolean joined = waitUntil(Duration.ofMinutes(2), () -> inWorld() && player().tickCount > 40);
        report.check("join " + address + " as " + self, joined, joined ? "in the world" : stateDescription());
        if (!joined) {
            screenshot("fail-skins-join");
            return;
        }
        String own = onGameThread(() -> skinDescription(self));
        report.check("own skin", own.startsWith("custom"), own);

        String[] seen = {"not in the player list"};
        boolean arrived = waitUntil(Duration.ofMinutes(3), () -> {
            seen[0] = onGameThread(() -> skinDescription(peer));
            return seen[0].equals(expectedSkin(peer));
        });
        report.check(peer + "'s skin from the server", arrived, seen[0]);
        if (arrived) {
            takeControl();
            command("tp " + peer);
            sleep(Duration.ofSeconds(2));
            injector().tap(62, 0x4000003E); // F5: third person, facing the peer from behind
            sleep(Duration.ofSeconds(1));
            screenshot("skins-" + peer);
        }
        // Stay for the peer's check.
        sleep(Duration.ofSeconds(30));
    }

    /**
     * Incari accounts and chat (ADR 0058, ADR 0059) against a real online service
     * ({@code incari.online.url}, an online service run locally): this player and {@code peer}, running
     * the same scenario at once, each make an account and join {@code address}. Each must see the
     * other as an Incari player there, and the chat menu must list them under On This Server. Then
     * A sends B a friend request by its per-server id and B accepts; they message as friends, and B
     * messages A by its per-server id (A lets everyone on the server message it); B blocks A, and
     * both see the friendship end. Last, each deletes its account, so runs don't pile them up.
     */
    private void onlineScenario(String peer, String address) throws Exception {
        waitForTitle();
        String self = minecraft.getUser().getName();
        boolean first = self.endsWith("A");
        incari.online.OnlineService online = onGameThread(incari.client.online.IncariOnline::service);
        boolean reached = waitUntil(Duration.ofSeconds(30), () -> {
            var status = online.status();
            return status == incari.framework.online.Online.Status.SIGNED_OUT
                    || status == incari.framework.online.Online.Status.SIGNED_IN;
        });
        report.check(
                "reach the online service",
                reached,
                onGameThread(() -> online.status().toString()));
        if (!reached) {
            return;
        }
        if (onGameThread(online::signedIn)) {
            // A session left by an earlier run.
            this.<Void>awaitOnline(done -> online.signOut(false, done));
        }
        // A fresh account each run: usernames are 3 to 16 characters.
        String username =
                "Smoke" + (first ? "A" : "B") + Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);
        String password = "smoke " + java.util.UUID.randomUUID();
        long started = System.nanoTime();
        incari.online.Result<java.util.List<String>> signedUp =
                this.<java.util.List<String>>awaitOnline(done -> online.signUp(username, password, done));
        report.check(
                "sign up as " + username,
                signedUp.ok() && signedUp.value().size() == 10,
                signedUp.ok()
                        ? "10 codes, " + (System.nanoTime() - started) / 1_000_000 + " ms with the proof of work"
                        : String.valueOf(signedUp.failure()));
        if (!signedUp.ok()) {
            return;
        }
        if (first) {
            // So B can also reach A by its per-server id, not only as a friend.
            this.<Void>awaitOnline(done -> online.setMessagesFrom(incari.online.Social.MessagesFrom.EVERYONE, done));
        }

        connect(address);
        boolean joined = waitUntil(Duration.ofMinutes(2), () -> inWorld() && player().tickCount > 40);
        report.check("join " + address + " as " + self, joined, joined ? "in the world" : stateDescription());
        if (!joined) {
            screenshot("fail-online-join");
            return;
        }
        java.util.concurrent.atomic.AtomicReference<incari.framework.online.@Nullable IncariPlayer> other =
                new java.util.concurrent.atomic.AtomicReference<>();
        boolean seen = waitUntil(Duration.ofMinutes(3), () -> {
            var connection = minecraft.getConnection();
            var info = connection == null ? null : connection.getPlayerInfo(peer);
            other.set(info == null ? null : online.player(info.getProfile().id()));
            return other.get() != null;
        });
        report.check(
                peer + " is an Incari player here",
                seen,
                seen ? "pid " + java.util.Objects.requireNonNull(other.get()).pid() : "not seen");
        if (!seen) {
            screenshot("fail-online-seen");
            return;
        }
        cosmeticsOnline(online, peer, first);
        if (first) {
            badgeShots(peer);
        }
        onGameThread(() -> {
            minecraft.gui.setScreen(new incari.client.online.ChatMenuScreen(null));
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("online-on-this-server");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });

        // Friends: A asks by B's id here; B accepts whatever arrives.
        if (first) {
            incari.framework.online.IncariPlayer target = java.util.Objects.requireNonNull(other.get());
            var asked = this.<Void>awaitOnline(done -> online.befriend(peer, target, done));
            report.check("send " + peer + " a friend request", asked.ok(), String.valueOf(asked.failure()));
        } else {
            boolean arrived =
                    waitUntil(Duration.ofMinutes(1), () -> online.social().pendingRequests() > 0);
            report.check("a friend request arrives", arrived, arrived ? "" : "none in a minute");
            if (arrived) {
                var request = onGameThread(() -> online.social().requests().get(0));
                var accepted = this.<Void>awaitOnline(done -> online.accept(request, done));
                report.check("accept it", accepted.ok(), String.valueOf(accepted.failure()));
            }
        }
        boolean friends =
                waitUntil(Duration.ofMinutes(1), () -> online.social().friends().size() == 1);
        report.check(
                "friends with " + peer,
                friends,
                onGameThread(() -> online.social().friends().toString()));
        if (!friends) {
            return;
        }
        var friend = onGameThread(() -> online.social().friends().get(0));
        var asFriend = new incari.online.Social.Peer(true, friend.fid(), friend.username());

        // Messages: A first, then B answers, then B by A's id on this server.
        String mine = "hello from " + self;
        String theirs = "hello from " + peer;
        if (first) {
            report.check(
                    "send a message",
                    this.<Void>awaitOnline(done -> online.sendMessage(asFriend, mine, done))
                            .ok(),
                    "");
        }
        boolean heard = waitUntil(
                Duration.ofMinutes(1),
                () -> online.social().conversation(asFriend).messages().stream()
                        .anyMatch(m -> !m.mine() && m.text().equals(theirs)));
        report.check("a message from " + peer, heard, heard ? "" : "nothing in a minute");
        if (!first) {
            report.check(
                    "answer it",
                    this.<Void>awaitOnline(done -> online.sendMessage(asFriend, mine, done))
                            .ok(),
                    "");
            var byPid = new incari.online.Social.Peer(
                    false, java.util.Objects.requireNonNull(other.get()).pid(), peer);
            var sent = this.<Void>awaitOnline(done -> online.sendMessage(byPid, "by your id here", done));
            report.check("message " + peer + " by its id on this server", sent.ok(), String.valueOf(sent.failure()));
        } else {
            boolean byId = waitUntil(
                    Duration.ofMinutes(1),
                    () -> online.social().playersMessaged().containsValue(peer));
            report.check(
                    "a message by id on this server",
                    byId,
                    onGameThread(() -> online.social().playersMessaged().toString()));
        }
        onGameThread(() -> {
            minecraft.gui.setScreen(new incari.client.online.ChatMenuScreen(null, asFriend));
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("online-conversation");
        onGameThread(() -> {
            minecraft.gui.setScreen(null);
            return null;
        });

        // B blocks A: the friendship ends on both sides, and A stops seeing B as an Incari player.
        if (!first) {
            var blocked = this.<Void>awaitOnline(done -> online.blockFriend(friend, done));
            report.check("block " + peer, blocked.ok(), String.valueOf(blocked.failure()));
        }
        boolean ended =
                waitUntil(Duration.ofMinutes(1), () -> online.social().friends().isEmpty());
        report.check(
                "the block ends the friendship",
                ended,
                onGameThread(() -> online.social().friends().toString()));
        if (first) {
            boolean hidden = waitUntil(Duration.ofSeconds(30), () -> {
                var connection = minecraft.getConnection();
                var info = connection == null ? null : connection.getPlayerInfo(peer);
                return info != null && online.player(info.getProfile().id()) == null;
            });
            report.check(peer + " no longer shows as an Incari player", hidden, "");
        }
        // Let the peer finish its checks, then clean up.
        sleep(Duration.ofSeconds(10));
        var deleted = this.<Void>awaitOnline(done -> online.deleteAccount(password, done));
        report.check("delete the account", deleted.ok(), String.valueOf(deleted.failure()));
    }

    /**
     * Incari's badge before {@code peer}'s name tag (ADR 0062), seen from 4 and 12 blocks: over RCON
     * ({@code incari.rcon}, password in {@code INCARI_RCON_PASSWORD}) a clear strip of snow is made
     * where this player stands, and both are put on it facing each other at noon.
     */
    private void badgeShots(String peer) throws Exception {
        Rcon rcon = new Rcon(
                System.getProperty("incari.rcon", "127.0.0.1:25592"),
                System.getenv().getOrDefault("INCARI_RCON_PASSWORD", "incari-local"));
        String self = minecraft.getUser().getName();
        net.minecraft.core.BlockPos at = onGameThread(() -> player().blockPosition());
        int x = at.getX();
        int y = at.getY();
        int z = at.getZ();
        try {
            rcon.run("time set noon");
            rcon.run("weather clear");
            rcon.run("fill " + (x - 3) + " " + (y - 1) + " " + (z - 16) + " " + (x + 3) + " " + (y - 1) + " " + (z + 1)
                    + " minecraft:snow_block");
            rcon.run("fill " + (x - 3) + " " + y + " " + (z - 16) + " " + (x + 3) + " " + (y + 3) + " " + (z + 1)
                    + " minecraft:air");
            rcon.run("tp " + self + " " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 180 0");
            for (int distance : new int[] {4, 12}) {
                rcon.run("tp " + peer + " " + (x + 0.5) + " " + y + " " + (z + 0.5 - distance) + " 0 0");
                sleep(Duration.ofSeconds(3));
                screenshot("online-badge-" + distance);
            }
            report.check("badge shots", true, "online-badge-4, online-badge-12");
        } catch (java.io.IOException e) {
            report.check("badge shots", false, "RCON: " + e);
        }
    }

    /**
     * ADR 0061 against a real service: each player wears something different, sees what the other
     * wears, sees the other's emote, and hides the other's cosmetics with the switch.
     */
    private void cosmeticsOnline(incari.online.OnlineService online, String peer, boolean first) throws Exception {
        incari.online.Wardrobe wardrobe = online.wardrobe();
        boolean synced = waitUntil(Duration.ofSeconds(30), wardrobe::shared);
        report.check("the account's wardrobe arrives", synced, "");
        if (!synced) {
            return;
        }
        java.util.List<String> mine = first
                ? java.util.List.of("cape.aurora", "hat.crown")
                : java.util.List.of("back.angel_wings", "nametag.gold");
        java.util.List<String> theirs = first
                ? java.util.List.of("back.angel_wings", "nametag.gold")
                : java.util.List.of("cape.aurora", "hat.crown");
        onGameThread(() -> {
            wardrobe.setShowOthers(true);
            wardrobe.wear(incari.online.Loadout.EMPTY);
            for (String id : mine) {
                wardrobe.wear(cosmetic(id));
            }
            return null;
        });
        java.util.function.Supplier<incari.framework.online.@Nullable IncariPlayer> peerNow = () -> {
            var connection = minecraft.getConnection();
            var info = connection == null ? null : connection.getPlayerInfo(peer);
            return info == null ? null : online.player(info.getProfile().id());
        };
        String[] last = {""};
        boolean wears = waitUntil(Duration.ofSeconds(45), () -> {
            var player = peerNow.get();
            last[0] =
                    player == null ? "not an Incari player" : player.cosmetics().toString();
            return player != null && player.cosmetics().containsAll(theirs);
        });
        report.check(peer + "'s cosmetics arrive", wears, last[0]);
        boolean drawn = onGameThread(() -> {
            var other = otherPlayer(peer);
            if (other == null) {
                return false;
            }
            var state = new net.minecraft.client.renderer.entity.state.AvatarRenderState();
            incari.client.cosmetics.Cosmetics.extract(other, state);
            return state.incariWorn != incari.client.cosmetics.Worn.NONE;
        });
        report.check(peer + "'s cosmetics are on their render state", drawn, "");

        // Emotes both ways: each plays one every few seconds until it has seen the other's.
        boolean sawEmote = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline && !sawEmote) {
            onGameThread(() -> incari.client.cosmetics.Cosmetics.playEmote(cosmetic("emote.wave")));
            sawEmote = waitUntil(Duration.ofMillis(2600), () -> {
                var other = otherPlayer(peer);
                if (other == null) {
                    return false;
                }
                var state = new net.minecraft.client.renderer.entity.state.AvatarRenderState();
                incari.client.cosmetics.Cosmetics.extract(other, state);
                return incari.client.cosmetics.Cosmetics.emoteOf(state) != null;
            });
        }
        report.check(peer + "'s emote arrives", sawEmote, "");
        // Go on playing a while, for the peer's check.
        for (int i = 0; i < 4; i++) {
            onGameThread(() -> incari.client.cosmetics.Cosmetics.playEmote(cosmetic("emote.wave")));
            sleep(Duration.ofMillis(2600));
        }
        onGameThread(() -> {
            minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            return null;
        });

        // The switch: the peer's cosmetics hide, and come back.
        boolean hidden = onGameThread(() -> {
            wardrobe.setShowOthers(false);
            var other = otherPlayer(peer);
            var state = new net.minecraft.client.renderer.entity.state.AvatarRenderState();
            if (other != null) {
                incari.client.cosmetics.Cosmetics.extract(other, state);
            }
            wardrobe.setShowOthers(true);
            return other != null && state.incariWorn == incari.client.cosmetics.Worn.NONE;
        });
        report.check("Show others' cosmetics off hides " + peer + "'s", hidden, "");
    }

    private net.minecraft.client.player.@Nullable AbstractClientPlayer otherPlayer(String name) {
        var level = minecraft.level;
        if (level == null) {
            return null;
        }
        for (var player : level.players()) {
            if (player.getGameProfile().name().equals(name)) {
                return player;
            }
        }
        return null;
    }

    /** Runs an online request on the game thread and waits for its answer (a minute at most). */
    private <T> incari.online.Result<T> awaitOnline(
            java.util.function.Consumer<java.util.function.Consumer<incari.online.Result<T>>> request)
            throws Exception {
        java.util.concurrent.CompletableFuture<incari.online.Result<T>> answer =
                new java.util.concurrent.CompletableFuture<>();
        onGameThread(() -> {
            request.accept(answer::complete);
            return null;
        });
        return answer.get(1, TimeUnit.MINUTES);
    }

    /**
     * Voice chat's signalling on an Eagler server with voice: this player turns global voice
     * on and waits to be paired with {@code peer} (running the same scenario), through the server,
     * with WebRTC scripted ({@link ScriptedRtc}): the offer, answer and candidates cross the server,
     * and each side lists the other as a voice.
     */
    private void voiceScenario(String peer, String address) throws Exception {
        waitForTitle();
        connect(address);
        boolean joined = waitUntil(Duration.ofMinutes(2), () -> inWorld() && player().tickCount > 40);
        report.check("join " + address, joined, joined ? "in the world" : stateDescription());
        if (!joined) {
            return;
        }
        boolean allowed = waitUntil(
                Duration.ofSeconds(20),
                () -> onGameThread(() -> {
                    var voice = incari.client.eagler.EaglerVoice.current();
                    return voice != null && voice.available();
                }));
        report.check("the server offers voice", allowed, allowed ? "voice allowed" : "no voice from the server");
        if (!allowed) {
            return;
        }
        onGameThread(() -> {
            var voice = incari.client.eagler.EaglerVoice.current();
            if (voice != null) {
                voice.setChannel(incari.client.eagler.VoiceSettings.Channel.GLOBAL);
            }
            return null;
        });
        String[] heard = {"nobody"};
        boolean paired = waitUntil(
                Duration.ofMinutes(2),
                () -> onGameThread(() -> {
                    var voice = incari.client.eagler.EaglerVoice.current();
                    if (voice == null) {
                        return false;
                    }
                    heard[0] = voice.speakers().toString();
                    return voice.speakers().stream().anyMatch(s -> s.name().equals(peer));
                }));
        report.check("paired with " + peer + " by voice", paired, heard[0]);
        // Stay for the peer's check.
        sleep(Duration.ofSeconds(20));
    }

    /**
     * The legacy behaviour on a 1.8 server, where the server itself can tell: sneaking's box and
     * eye height, the pick margin, no attack cooldown, a ladder's thickness, and a held sword
     * blocking, which the 1.8 server shows by halving an arrow's damage ((1 + d) / 2). Sets the
     * world up over RCON ({@code incari.rcon}, password in {@code INCARI_RCON_PASSWORD}).
     */
    private void legacyScenario(String address) throws Exception {
        Rcon rcon = new Rcon(
                System.getProperty("incari.rcon", "127.0.0.1:25611"),
                System.getenv().getOrDefault("INCARI_RCON_PASSWORD", "incari-dev"));
        String name = minecraft.getUser().getName();
        waitForTitle();
        connect(address);
        boolean joined = waitUntil(Duration.ofMinutes(2), () -> inWorld() && player().tickCount > 40);
        report.check("join " + address, joined, joined ? versionDescription() : stateDescription());
        if (!joined) {
            return;
        }
        takeControl();
        rcon.run("difficulty 0");
        rcon.run("time set 1000");
        rcon.run("gamemode 0 " + name);
        rcon.run("clear " + name);
        rcon.run("effect " + name + " 6 1 10");
        sleep(Duration.ofSeconds(2));

        int protocol = onGameThread(incari.client.legacy.LegacyRules::protocol);
        report.check(
                "rules follow the server",
                protocol == 47 && incari.client.legacy.LegacyRules.combat18(),
                "protocol " + protocol);
        float pick = onGameThread(() -> player().getPickRadius());
        report.check("1.8's pick margin", Math.abs(pick - 0.1F) < 1e-6, "pick radius " + pick);
        float strength = onGameThread(() -> {
            player().resetAttackStrengthTicker();
            return player().getAttackStrengthScale(0.0F);
        });
        report.check("no attack cooldown", strength == 1.0F, "strength right after a swing " + strength);

        injector().key(225, 0x400000E1, true); // left shift: sneak
        sleep(Duration.ofMillis(800));
        float[] sneaking = onGameThread(() -> new float[] {player().getBbHeight(), player().getEyeHeight()});
        injector().key(225, 0x400000E1, false);
        report.check(
                "1.8's sneaking box",
                Math.abs(sneaking[0] - 1.8F) < 1e-4 && Math.abs(sneaking[1] - 1.54F) < 1e-4,
                "height " + sneaking[0] + ", eyes " + sneaking[1]);

        net.minecraft.core.BlockPos ladder =
                onGameThread(() -> player().blockPosition().offset(3, 0, 0));
        rcon.run("setblock " + ladder.getX() + " " + ladder.getY() + " " + ladder.getZ() + " minecraft:ladder 2");
        boolean placed = waitUntil(
                Duration.ofSeconds(5),
                () -> onGameThread(
                        () -> level().getBlockState(ladder).is(net.minecraft.world.level.block.Blocks.LADDER)));
        double thickness = onGameThread(() -> {
            var bounds = level().getBlockState(ladder)
                    .getCollisionShape(level(), ladder)
                    .bounds();
            return Math.min(bounds.getXsize(), bounds.getZsize());
        });
        report.check("1.8's ladder", placed && Math.abs(thickness - 0.125) < 1e-6, "thickness " + thickness);
        rcon.run("setblock " + ladder.getX() + " " + ladder.getY() + " " + ladder.getZ() + " minecraft:air");

        rcon.run("give " + name + " minecraft:diamond_sword 1");
        waitUntil(
                Duration.ofSeconds(5),
                () -> onGameThread(() -> !player().getInventory().getItem(0).isEmpty()));
        injector().tap(30, '1'); // the first hotbar slot
        sleep(Duration.ofMillis(500));
        float open = arrowDamage(rcon, name);
        injector().mouseButton(3, true); // right button: use, which raises the sword
        boolean raised = waitUntil(
                Duration.ofSeconds(3),
                () -> onGameThread(() -> player().isUsingItem()
                        && player().getUseItem().getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.BLOCK));
        screenshot("legacy-blocking");
        float blocked = arrowDamage(rcon, name);
        injector().mouseButton(3, false);
        report.check(
                "sword blocking",
                raised && open > 0 && Math.abs(blocked - (1 + open) / 2) < 0.05F,
                "raised " + raised + "; an arrow took " + open + " open, " + blocked + " blocking");
    }

    /** Health an arrow dropped onto the player takes, after healing it to full. */
    private float arrowDamage(Rcon rcon, String name) throws Exception {
        rcon.run("effect " + name + " 6 1 10");
        waitUntil(Duration.ofSeconds(5), () -> onGameThread(() -> player().getHealth() >= player().getMaxHealth()));
        sleep(Duration.ofMillis(600)); // past the hurt cooldown of any earlier hit
        float before = onGameThread(() -> player().getHealth());
        net.minecraft.world.phys.Vec3 at = onGameThread(() -> player().position());
        rcon.run(String.format(
                java.util.Locale.ROOT,
                "summon Arrow %.2f %.2f %.2f {Motion:[0.0,-1.5,0.0],damage:2.0}",
                at.x,
                at.y + 3.2,
                at.z));
        // Read at the first change: peaceful's regeneration would add to it a second later.
        float[] after = {before};
        waitUntil(Duration.ofSeconds(4), () -> (after[0] = onGameThread(() -> player().getHealth())) < before);
        return before - after[0];
    }

    /**
     * Password logins and cookies, against an Eagler server whose auth plugin wants a
     * password for this player's name: the password screen comes up, the password typed there
     * logs in, the server's cookie is kept, and the next join logs in by the cookie alone.
     */
    private void authScenario(String address) throws Exception {
        waitForTitle();
        Path cookies = minecraft.gameDirectory.toPath().resolve("incari").resolve("eagler-cookies.txt");
        Files.deleteIfExists(cookies);
        connect(address);
        boolean asked = waitUntil(
                Duration.ofSeconds(30),
                () -> onGameThread(() -> minecraft.gui.screen() instanceof incari.client.eagler.PasswordScreen));
        report.check("the server asks for a password", asked, asked ? "the password screen is up" : stateDescription());
        if (!asked) {
            screenshot("fail-auth-screen");
            return;
        }
        screenshot("auth-password-screen");
        if (input == null) {
            // Not takeControl(): grabbing the mouse closes the screen being typed into.
            int windowId = onGameThread(
                    () -> SDLVideo.SDL_GetWindowID(minecraft.getWindow().handle()));
            input = new SdlInjector(windowId);
        }
        injector().focus();
        sleep(Duration.ofMillis(300));
        injector().text("incari");
        sleep(Duration.ofMillis(200));
        injector().tap(40, 0x0D); // Enter logs in
        boolean joined = waitUntil(Duration.ofMinutes(1), () -> inWorld() && player().tickCount > 40);
        report.check("join with the password", joined, joined ? "in the world" : stateDescription());
        if (!joined) {
            screenshot("fail-auth-join");
            return;
        }
        boolean kept = waitUntil(Duration.ofSeconds(15), () -> Files.isRegularFile(cookies));
        report.check("the server's cookie is kept", kept, kept ? cookies.toString() : "no cookie file");
        injector().tap(41, 0x1B); // Escape
        waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof PauseScreen);
        clickButton("menu.disconnect");
        waitUntil(Duration.ofMinutes(1), () -> minecraft.level == null && minecraft.getConnection() == null);
        sleep(Duration.ofSeconds(2));

        // A fresh session: no password in memory, only the cookie.
        onGameThread(() -> {
            incari.client.eagler.EaglerAuth.forget(address);
            return null;
        });
        connect(address);
        boolean[] askedAgain = {false};
        boolean rejoined = waitUntil(Duration.ofMinutes(1), () -> {
            askedAgain[0] |= onGameThread(() -> minecraft.gui.screen() instanceof incari.client.eagler.PasswordScreen);
            return inWorld() && player().tickCount > 40;
        });
        report.check(
                "join with the cookie",
                rejoined && !askedAgain[0],
                rejoined
                        ? (askedAgain[0] ? "asked for the password again" : "logged in without one")
                        : stateDescription());
    }

    /** M2: the screens Incari adds, drawn: the edit-server screen's Version button, the Eagler skin screen. */
    private void screensScenario() {
        waitForTitle();
        onGameThread(() -> {
            net.minecraft.client.multiplayer.ServerData server = new net.minecraft.client.multiplayer.ServerData(
                    "Test", "ws://127.0.0.1:8801", ServerData.Type.OTHER);
            server.incariVersion = 47;
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ManageServerScreen(
                    new TitleScreen(),
                    net.minecraft.network.chat.Component.literal("Edit Server Info"),
                    ok -> {},
                    server));
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("screen-edit-server");
        onGameThread(() -> {
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.options.SkinCustomizationScreen(
                    new TitleScreen(), minecraft.options));
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("screen-skin-customisation");
        onGameThread(() -> {
            minecraft.gui.setScreen(incari.client.profile.ProfileScreen.from(new TitleScreen()));
            return null;
        });
        sleep(Duration.ofSeconds(2));
        screenshot("screen-edit-profile");
        report.check("screens drawn", true, "screenshots in incari-smoke/screenshots");
        screenEdits();
        skin();
    }

    /**
     * The game's GUI redrawn by a skin ({@code incari.client.ui.Skins}): a flat test skin draws
     * buttons' sprites, the separators, the backdrop and one widget whole, on the title screen and
     * the video settings; with no skin the game's look is back; a skin that throws is dropped.
     */
    private void skin() {
        FlatTestSkin skin = new FlatTestSkin();
        onGameThread(() -> {
            incari.client.ui.Skins.use(skin);
            minecraft.gui.setScreen(new TitleScreen());
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("skin-title");
        onGameThread(() -> {
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                    shownScreen(), minecraft, minecraft.options));
            return null;
        });
        sleep(Duration.ofSeconds(1));
        screenshot("skin-video-settings");
        String drawn = onGameThread(() -> skin.widgets + " widgets, " + skin.sprites + " sprites, " + skin.textures
                + " textures, " + skin.backgrounds + " backdrops");
        boolean all =
                onGameThread(() -> skin.widgets > 0 && skin.sprites > 0 && skin.textures > 0 && skin.backgrounds > 0);
        report.check("skin drew the GUI", all, drawn);

        int before = onGameThread(() -> {
            incari.client.ui.Skins.use(null);
            return skin.sprites;
        });
        sleep(Duration.ofMillis(500));
        int after = onGameThread(() -> skin.sprites);
        report.check("no skin, the game's look", before == after, (after - before) + " sprites drawn by the old skin");

        onGameThread(() -> {
            incari.client.ui.Skins.use(new incari.client.ui.GuiSkin() {
                @Override
                public boolean background(
                        net.minecraft.client.gui.GuiGraphicsExtractor graphics, Screen screen, float a) {
                    throw new IllegalStateException("a broken skin");
                }
            });
            return null;
        });
        sleep(Duration.ofMillis(500));
        boolean dropped = onGameThread(() -> incari.client.ui.Skins.current() == null);
        report.check("a skin that throws is dropped", dropped, "");
        screenshot("skin-dropped");
        onGameThread(() -> {
            minecraft.gui.setScreen(new TitleScreen());
            return null;
        });
    }

    /** A flat look for {@link #skin}, counting what it drew. Render thread. */
    private static final class FlatTestSkin implements incari.client.ui.GuiSkin {
        int widgets;
        int sprites;
        int textures;
        int backgrounds;

        @Override
        public boolean widget(
                net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                AbstractWidget widget,
                int mouseX,
                int mouseY,
                float a) {
            if (!widget.getMessage().getString().equals("Edited")) {
                return false;
            }
            graphics.fill(
                    widget.getX(),
                    widget.getY(),
                    widget.getX() + widget.getWidth(),
                    widget.getY() + widget.getHeight(),
                    widget.isHovered() ? 0xFFFFD54F : 0xFFFFB300);
            graphics.centeredText(
                    Minecraft.getInstance().font,
                    widget.getMessage(),
                    widget.getX() + widget.getWidth() / 2,
                    widget.getY() + (widget.getHeight() - 8) / 2,
                    0xFF000000);
            incari.client.ui.Skins.cursor(graphics, widget);
            widgets++;
            return true;
        }

        @Override
        public boolean sprite(
                net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                net.minecraft.resources.Identifier sprite,
                int x,
                int y,
                int width,
                int height,
                int color) {
            String path = sprite.getPath();
            if (!path.startsWith("widget/button")) {
                return false;
            }
            int fill =
                    path.endsWith("_highlighted") ? 0xFF3A7BD5 : path.endsWith("_disabled") ? 0xFF555555 : 0xFF263238;
            graphics.fill(x, y, x + width, y + height, fill);
            sprites++;
            return true;
        }

        @Override
        public boolean texture(
                net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                net.minecraft.resources.Identifier texture,
                int x,
                int y,
                int width,
                int height,
                float u,
                float v,
                int srcWidth,
                int srcHeight,
                int textureWidth,
                int textureHeight,
                int color) {
            if (!texture.getPath().endsWith("_separator.png")) {
                return false;
            }
            graphics.fill(x, y, x + width, y + 1, 0xFF3A7BD5);
            textures++;
            return true;
        }

        @Override
        public boolean background(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Screen screen, float a) {
            graphics.fill(0, 0, screen.width, screen.height, 0xFF101418);
            backgrounds++;
            return true;
        }
    }

    /**
     * The game's screens changed without a patch each ({@code incari.client.ui.ScreenEdits}): the
     * title screen's Realms button removed, Quit moved and a button added, all still so after a
     * resize; and a screen of the game's replaced.
     */
    private void screenEdits() {
        String edited = onGameThread(() -> {
            incari.client.ui.ScreenEdits.edit(TitleScreen.class, screen -> {
                screen.remove("menu.online");
                AbstractWidget quit = screen.find("menu.quit");
                if (quit != null) {
                    quit.setPosition(4, 4);
                }
                screen.add(incari.client.ui.Ui.button(
                                net.minecraft.network.chat.Component.literal("Edited"), 80, () -> {}))
                        .setPosition(screen.width() - 84, 4);
            });
            incari.client.ui.ScreenEdits.replace(
                    net.minecraft.client.gui.screens.CreditsAndAttributionScreen.class,
                    credits -> new net.minecraft.client.gui.screens.GenericMessageScreen(
                            net.minecraft.network.chat.Component.literal("Replaced by Incari")));
            minecraft.gui.setScreen(new TitleScreen());
            String opened = titleEdits();
            Screen title = shownScreen();
            title.resize(title.width - 20, title.height);
            return opened + "; after a resize: " + titleEdits();
        });
        sleep(Duration.ofSeconds(1));
        screenshot("screen-edits-title");
        String expected = "no Realms, Quit at 4,4, 1 added; after a resize: no Realms, Quit at 4,4, 1 added";
        report.check("title screen edited", edited.equals(expected), edited);

        String replaced = onGameThread(() -> {
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.CreditsAndAttributionScreen(shownScreen()));
            return shownScreen().getClass().getSimpleName();
        });
        report.check("screen replaced", replaced.equals("GenericMessageScreen"), replaced);
        onGameThread(() -> {
            minecraft.gui.setScreen(new TitleScreen());
            return null;
        });
    }

    /** The screen shown, which a scenario knows there is. Game thread. */
    private Screen shownScreen() {
        return java.util.Objects.requireNonNull(minecraft.gui.screen(), "no screen");
    }

    /** What the title screen's edits did, as {@link #screenEdits} expects it. Game thread. */
    private String titleEdits() {
        boolean realms = false;
        String quit = "no Quit";
        int added = 0;
        for (var child : shownScreen().children()) {
            if (child instanceof AbstractWidget widget) {
                String key = widget.getMessage().getContents() instanceof TranslatableContents label
                        ? label.getKey()
                        : widget.getMessage().getString();
                realms |= key.equals("menu.online");
                if (key.equals("menu.quit")) {
                    quit = "Quit at " + widget.getX() + "," + widget.getY();
                }
                added += key.equals("Edited") ? 1 : 0;
            }
        }
        return (realms ? "Realms" : "no Realms") + ", " + quit + ", " + added + " added";
    }

    /** A player saved dead on the server rejoins on the death screen: respawn. */
    private void respawnIfDead() {
        if (onGameThread(() -> minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen)) {
            onGameThread(() -> {
                player().respawn();
                minecraft.gui.setScreen(null);
                return null;
            });
        }
    }

    /** A 64x64 skin in one colour made from {@code name} (red kept even: Eagler sends 7 bits of it). */
    private static int skinColour(String name) {
        int hash = name.hashCode() * 0x9E3779B1;
        return 0xFF000000 | (((hash >>> 16) & 0xFE) << 16) | (((hash >>> 8) & 0xFF) << 8) | (hash & 0xFF);
    }

    private void writeGeneratedSkin(String name, boolean slim) throws java.io.IOException {
        Path folder = minecraft.gameDirectory.toPath().resolve("incari").resolve("skin");
        java.nio.file.Files.createDirectories(folder.resolve("library"));
        try (com.mojang.blaze3d.platform.NativeImage image =
                new com.mojang.blaze3d.platform.NativeImage(64, 64, false)) {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    // A slim skin leaves the wide arm's last columns empty (OwnSkin reads its model from them).
                    boolean wideOnly = x >= 54 && x < 56 && y >= 20 && y < 32;
                    image.setPixel(x, y, slim && wideOnly ? 0 : skinColour(name));
                }
            }
            image.writeToFile(folder.resolve("library").resolve("generated.png"));
        }
        if (slim) {
            try (com.mojang.blaze3d.platform.NativeImage cape =
                    new com.mojang.blaze3d.platform.NativeImage(64, 32, false)) {
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 64; x++) {
                        cape.setPixel(x, y, 0xFF3060C0);
                    }
                }
                cape.writeToFile(folder.resolve("cape.png"));
            }
        } else {
            java.nio.file.Files.deleteIfExists(folder.resolve("cape.png"));
        }
        java.nio.file.Files.writeString(folder.resolve("skin.properties"), "skin=custom:generated.png\n");
    }

    private static String expectedSkin(String name) {
        boolean slim = name.endsWith("A");
        return "custom " + (slim ? "slim" : "wide") + " #" + Integer.toHexString(skinColour(name))
                + (slim ? " with cape #ff3060c0" : " no cape");
    }

    /** How the game will draw {@code name}'s skin: its texture's colour, model and cape. */
    private String skinDescription(String name) {
        var connection = minecraft.getConnection();
        if (connection == null) {
            return "no connection";
        }
        var info = connection.getPlayerInfo(name);
        if (info == null) {
            return "not in the player list";
        }
        net.minecraft.world.entity.player.PlayerSkin skin = info.getSkin();
        String kind = pixel(skin.body().texturePath(), 10, 10);
        if (kind == null) {
            return "a default skin (" + skin.body().texturePath() + ")";
        }
        String cape = skin.cape() == null
                ? " no cape"
                : " with cape " + pixel(skin.cape().texturePath(), 1, 1);
        return "custom " + (skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "wide")
                + " " + kind + cape;
    }

    private @org.jspecify.annotations.Nullable String pixel(net.minecraft.resources.Identifier texture, int x, int y) {
        if (!texture.getNamespace().equals("incari")) {
            return null;
        }
        var loaded = minecraft.getTextureManager().getTexture(texture);
        if (!(loaded instanceof net.minecraft.client.renderer.texture.DynamicTexture dynamic)
                || dynamic.getPixels() == null) {
            return "(no pixels)";
        }
        return "#" + Integer.toHexString(dynamic.getPixels().getPixel(x, y));
    }

    /** Whether the last join translates through Via (the server speaks another version). */
    private boolean viaInPipeline() {
        return incari.client.net.ServerConnections.lastJoinedProtocol()
                != net.minecraft.SharedConstants.getCurrentVersion().protocolVersion();
    }

    private String versionDescription() {
        int protocol = incari.client.net.ServerConnections.lastJoinedProtocol();
        return incari.client.net.ServerVersions.name(protocol) + " (protocol " + protocol + ")"
                + (viaInPipeline() ? " through Via" : "");
    }

    private void stayConnected(Duration duration) {
        boolean dropped = waitUntil(duration, () -> !inWorld() || minecraft.getConnection() == null);
        report.check(
                "stay connected for " + duration.toSeconds() + " s",
                !dropped,
                dropped ? stateDescription() : "still in the world");
    }

    /** A server list ping: handshake, status and ping packets, as the multiplayer screen sends them. */
    private void ping(String address) throws Exception {
        ServerData data = new ServerData("Incari smoke server", address, ServerData.Type.OTHER);
        ServerStatusPinger pinger = new ServerStatusPinger();
        pinger.pingServer(data, () -> {}, () -> {}, EventLoopGroupHolder.remote(false));
        boolean answered = waitUntil(Duration.ofSeconds(15), () -> {
            pinger.tick();
            return data.motd != null && data.ping >= 0 && data.players != null;
        });
        report.check(
                "server list ping to " + address,
                answered,
                answered ? "motd '" + data.motd.getString() + "', " + data.ping + " ms" : "no status reply");
        pinger.removeAll();
    }

    private void connect(String address) {
        onGameThread(() -> {
            ConnectScreen.startConnecting(
                    new TitleScreen(),
                    minecraft,
                    ServerAddress.parseString(address),
                    new ServerData("Incari smoke server", address, ServerData.Type.OTHER),
                    false,
                    null);
            return null;
        });
    }

    /** How the current connection reaches the server, for the report. */
    private String transportDescription() {
        return onGameThread(() -> {
            var listener = minecraft.getConnection();
            if (listener == null) {
                return "no connection";
            }
            var remote = listener.getConnection().getRemoteAddress();
            return (remote instanceof incari.net.WebSocketAddress ? "a WebSocket to " : "TCP to ") + remote;
        });
    }

    /**
     * Asks the server to transfer the player to {@code address} (the same server): the client
     * disconnects and connects again by itself. A {@code ws://} address travels in the packet's host
     * field. The server needs {@code accepts-transfers=true}.
     */
    private void transfer(String address) {
        Object before = onGameThread(() -> minecraft.getConnection());
        String target = address.contains("://") ? address : address.substring(0, address.lastIndexOf(':'));
        String port = address.contains("://") ? "" : " " + address.substring(address.lastIndexOf(':') + 1);
        command("transfer \"" + target + "\"" + port);
        boolean transferred = waitUntil(
                Duration.ofMinutes(1),
                () -> minecraft.level != null
                        && minecraft.player != null
                        && !java.util.Objects.equals(minecraft.getConnection(), before)
                        && minecraft.player.tickCount > 20);
        String connection = onGameThread(() ->
                (java.util.Objects.equals(minecraft.getConnection(), before) ? "the same connection" : "a new one")
                        + ", player ticks " + (minecraft.player == null ? -1 : minecraft.player.tickCount));
        report.check(
                "transfer to " + address,
                transferred,
                transferred
                        ? "reconnected by itself over " + transportDescription()
                        : connection + ", " + stateDescription());
    }

    /** Teleports into another dimension with a command and checks the client follows. */
    private void changeDimension(String dimension) {
        command("execute in " + dimension + " run tp @s 0 100 0");
        boolean changed = waitUntil(
                Duration.ofMinutes(1),
                () -> inWorld()
                        && level().dimension().identifier().toString().equals(dimension)
                        && player().tickCount > 20);
        report.check(
                "change dimension to " + dimension,
                changed,
                changed ? "the client is in " + dimension : stateDescription());
        if (changed) {
            screenshot("dimension-" + dimension.substring(dimension.indexOf(':') + 1));
        }
    }

    // ----------------------------------------------------------------------------------------
    // Checks
    // ----------------------------------------------------------------------------------------

    private void waitForTitle() {
        // The title screen exists behind the loading overlay; wait until the overlay is gone too.
        boolean title = waitUntil(
                Duration.ofMinutes(3),
                () -> minecraft.gui.screen() instanceof TitleScreen && minecraft.gui.overlay() == null);
        report.check("boot to the title screen", title, title ? "" : "screen: " + minecraft.gui.screen());
        screenshot("01-title");
    }

    /** Focuses the window and grabs the mouse, as clicking into the game does. */
    private void takeControl() {
        int windowId = onGameThread(
                () -> SDLVideo.SDL_GetWindowID(minecraft.getWindow().handle()));
        onGameThread(() -> {
            minecraft.gui.hud.getChat().setVisibleMessageFilter(message -> {
                seenChat.add(message.content().getString());
                return true;
            });
            return null;
        });
        SdlInjector injector = new SdlInjector(windowId);
        input = injector;
        injector.focus();
        sleep(Duration.ofMillis(300));
        onGameThread(() -> {
            minecraft.mouseHandler.grabMouse();
            return null;
        });
        waitUntil(Duration.ofSeconds(5), () -> minecraft.mouseHandler.isMouseGrabbed());
    }

    private void walk() {
        Vec3 start = onGameThread(() -> player().position());
        // Space with W climbs the one-block steps the spawn point often faces.
        injector().key(44, ' ', true);
        holdKey(26, 'w', Duration.ofSeconds(2)); // W
        injector().key(44, ' ', false);
        Vec3 end = onGameThread(() -> player().position());
        double moved = horizontalDistance(start, end);
        report.check("walk (W key)", moved > 1.0, String.format(java.util.Locale.ROOT, "moved %.1f blocks", moved));
    }

    private void jump() {
        // Start from the ground: the walk before may have ended mid-climb.
        waitUntil(Duration.ofSeconds(3), () -> player().onGround());
        sleep(Duration.ofMillis(200));
        double before = onGameThread(() -> player().getY());
        injector().key(44, ' ', true); // Space
        double highest = before;
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
        while (System.nanoTime() < until) {
            highest = Math.max(highest, onGameThread(() -> player().getY()));
            sleep(Duration.ofMillis(20));
        }
        injector().key(44, ' ', false);
        report.check(
                "jump (Space)",
                highest - before > 0.5,
                String.format(java.util.Locale.ROOT, "rose %.2f blocks", highest - before));
    }

    private void look() {
        // The game only turns the player while its window is active, and a real focus change
        // from the desktop (someone using the machine) can land after the injected one.
        injector().focus();
        sleep(Duration.ofMillis(100));
        float before = onGameThread(() -> player().getYRot());
        for (int i = 0; i < 10; i++) {
            injector().look(40, 0);
            sleep(Duration.ofMillis(30));
        }
        sleep(Duration.ofMillis(200));
        float after = onGameThread(() -> player().getYRot());
        boolean active = onGameThread(() -> minecraft.isWindowActive());
        report.check(
                "look around (mouse motion)",
                Math.abs(after - before) > 5,
                "turned " + Math.round(after - before) + " degrees" + (active ? "" : ", window not active"));
    }

    private void breakAndPlace() {
        // Aim at the ground a little ahead: not the block the player stands in, which a new
        // block couldn't be placed back into. (Looking itself was checked with mouse input.)
        onGameThread(() -> {
            player().setXRot(60.0F);
            return null;
        });
        sleep(Duration.ofMillis(300));
        BlockPos target = onGameThread(
                () -> minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                        ? hit.getBlockPos()
                        : null);
        if (target == null) {
            report.fail("break a block (left mouse)", "not looking at a block");
            return;
        }
        String before = onGameThread(() -> blockName(level().getBlockState(target)));
        injector().mouseButton(1, true);
        boolean broken = waitUntil(
                Duration.ofSeconds(8), () -> level().getBlockState(target).isAir());
        injector().mouseButton(1, false);
        report.check("break a block (left mouse)", broken, before + " at " + target.toShortString());
        if (!broken) {
            return;
        }
        // Snow and sand don't drop by hand, and the spawn biome varies: get a block to place
        // with a command instead, typed through chat like a player would.
        command("give @s minecraft:dirt 8");
        boolean given = waitUntil(
                Duration.ofSeconds(5),
                () -> !player().getInventory().getSelectedItem().isEmpty());
        report.check(
                "run a command (/give)",
                given,
                onGameThread(() -> player().getInventory().getSelectedItem().toString()));

        // The block below the hole is now the target; place into the hole.
        sleep(Duration.ofMillis(300));
        injector().mouseButton(3, true);
        sleep(Duration.ofMillis(100));
        injector().mouseButton(3, false);
        boolean placed = waitUntil(
                Duration.ofSeconds(3), () -> !level().getBlockState(target).isAir());
        report.check(
                "place a block (right mouse)", placed, onGameThread(() -> blockName(level().getBlockState(target))));
    }

    /**
     * Clicks the button whose label has this translation key, with injected pointer events at its
     * centre: the same path as a player clicking a menu.
     */
    private void clickButton(String translationKey) {
        float[] centre = buttonCentre(translationKey);
        if (centre == null) {
            report.fail("click " + translationKey, "no such button on " + stateDescription());
            return;
        }
        injector().moveTo(centre[0], centre[1]);
        sleep(Duration.ofMillis(150));
        injector().mouseButton(1, true);
        sleep(Duration.ofMillis(80));
        injector().mouseButton(1, false);
    }

    /** The pointer over the shown screen's button labelled {@code translationKey}, if it has one. */
    private void hoverButton(String translationKey) {
        float[] centre = buttonCentre(translationKey);
        if (centre != null) {
            injector().moveTo(centre[0], centre[1]);
        }
    }

    /** The centre in window coordinates of the shown screen's button labelled {@code translationKey}. */
    private float @Nullable [] buttonCentre(String translationKey) {
        return onGameThread(() -> {
            if (minecraft.gui.screen() == null) {
                return null;
            }
            for (var child : minecraft.gui.screen().children()) {
                if (child instanceof AbstractWidget widget
                        && ((widget.getMessage().getContents() instanceof TranslatableContents label
                                        && label.getKey().equals(translationKey))
                                // An Incari button's label is literal text.
                                || widget.getMessage().getString().equals(translationKey))) {
                    // GUI units to window coordinates: the GUI spans the whole window.
                    var window = minecraft.getWindow();
                    float toWindowX = (float) window.getScreenWidth() / window.getGuiScaledWidth();
                    float toWindowY = (float) window.getScreenHeight() / window.getGuiScaledHeight();
                    return new float[] {
                        (widget.getX() + widget.getWidth() / 2.0F) * toWindowX,
                        (widget.getY() + widget.getHeight() / 2.0F) * toWindowY
                    };
                }
            }
            return null;
        });
    }

    /** Types a command into chat and sends it. */
    private void command(String command) {
        injector().tap(56, '/'); // Slash opens chat with "/" typed
        waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof ChatScreen);
        sleep(Duration.ofMillis(200));
        injector().text(command);
        sleep(Duration.ofMillis(200));
        injector().tap(40, 0x0D); // Enter
        waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() == null);
    }

    private void inventory() {
        injector().tap(8, 'e'); // E
        boolean open = waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof InventoryScreen);
        report.check("open the inventory (E)", open, "");
        if (open) {
            screenshot("03-inventory");
            injector().tap(41, 0x1B); // Escape
            report.check(
                    "close the inventory (Escape)",
                    waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() == null),
                    "");
        }
    }

    private void chat(String message) {
        injector().tap(23, 't'); // T
        boolean open = waitUntil(Duration.ofSeconds(3), () -> minecraft.gui.screen() instanceof ChatScreen);
        if (!open) {
            report.fail("chat (T, typing, Enter)", "the chat screen didn't open");
            return;
        }
        sleep(Duration.ofMillis(200));
        injector().text(message);
        sleep(Duration.ofMillis(200));
        injector().tap(40, 0x0D); // Enter
        boolean echoed = waitUntil(Duration.ofSeconds(5), () -> chatContains(message));
        report.check("chat (T, typing, Enter)", echoed, echoed ? "the server echoed it" : "no echo");
        if (echoed) {
            screenshot("04-chat");
        }
    }

    private void sound() {
        String channels = onGameThread(() -> minecraft.getSoundManager().getChannelDebugString());
        report.check("sound engine", !channels.isBlank(), channels);
    }

    private void lan(int waitForJoinSeconds) throws Exception {
        int port = LAN_PORT;
        boolean published = onGameThread(() -> {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server == null) {
                return false;
            }
            // Vanilla LAN worlds always authenticate guests with Mojang, which offline test
            // clients can't: the same as online-mode=false on a dedicated server.
            server.setUsesAuthentication(false);
            return server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port);
        });
        report.check("open to LAN", published, "port " + port);
        if (!published) {
            return;
        }

        // A server list ping: handshake, status and ping packets through PlatformSocketChannel.
        ServerData data = new ServerData("LAN", "127.0.0.1:" + port, ServerData.Type.OTHER);
        ServerStatusPinger pinger = new ServerStatusPinger();
        pinger.pingServer(data, () -> {}, () -> {}, EventLoopGroupHolder.remote(false));
        boolean answered = waitUntil(Duration.ofSeconds(15), () -> {
            pinger.tick();
            return data.motd != null && data.ping >= 0 && data.players != null;
        });
        report.check(
                "server list ping over the platform transport",
                answered,
                answered ? "motd '" + data.motd.getString() + "', " + data.ping + " ms" : "no status reply");
        pinger.removeAll();

        if (waitForJoinSeconds > 0) {
            boolean joined = waitUntil(Duration.ofSeconds(waitForJoinSeconds), () -> playersOnLanServer() > 1);
            report.check("a second client joined the LAN world", joined, "");
            if (joined) {
                boolean greeted = waitUntil(Duration.ofSeconds(30), () -> chatContains("hello from the second client"));
                report.check("chat from the second client arrived", greeted, "");
                screenshot("05-lan-guest");
                waitUntil(Duration.ofSeconds(60), () -> playersOnLanServer() == 1);
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------

    private net.minecraft.client.player.LocalPlayer player() {
        net.minecraft.client.player.LocalPlayer player = minecraft.player;
        if (player == null) {
            throw new IllegalStateException("Not in a world");
        }
        return player;
    }

    private net.minecraft.client.multiplayer.ClientLevel level() {
        net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
        if (level == null) {
            throw new IllegalStateException("Not in a world");
        }
        return level;
    }

    private int playersOnLanServer() {
        IntegratedServer server = minecraft.getSingleplayerServer();
        return server == null ? 0 : server.getPlayerCount();
    }

    /** What the game shows right now, for a failed check's report line. */
    private String stateDescription() {
        return onGameThread(() -> "screen "
                + (minecraft.gui.screen() == null
                        ? "none"
                        : minecraft.gui.screen().getClass().getSimpleName())
                + ", player " + (minecraft.player == null ? "none" : "present")
                + ", level " + (minecraft.level == null ? "none" : "present")
                + ", connection " + (minecraft.getConnection() == null ? "none" : "present"));
    }

    private boolean inWorld() {
        return onGameThread(
                () -> minecraft.player != null && minecraft.level != null && minecraft.gui.screen() == null);
    }

    private boolean chatContains(String text) {
        return seenChat.stream().anyMatch(line -> line.contains(text));
    }

    private void holdKey(int scancode, int keyCode, Duration duration) {
        injector().key(scancode, keyCode, true);
        sleep(duration);
        injector().key(scancode, keyCode, false);
        sleep(Duration.ofMillis(300));
    }

    private SdlInjector injector() {
        SdlInjector current = input;
        if (current == null) {
            throw new IllegalStateException("takeControl() first");
        }
        return current;
    }

    private void screenshot(String name) {
        File folder = directory.toFile();
        onGameThread(() -> {
            Screenshot.grab(folder, name + ".png", minecraft.gameRenderer.mainRenderTarget(), 1, message -> {});
            return null;
        });
        sleep(Duration.ofMillis(500));
    }

    private static String blockName(BlockState state) {
        return state.getBlock().getName().getString();
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Polls {@code condition} on the game thread until it holds or the time runs out. The game
     * thread can be busy for a long time (generating a world), so a slow answer isn't a failure:
     * the same query is waited on for a while instead of queueing new ones. Only a while: leaving a
     * server drops the game's queued tasks, so a query asked just before a transfer never answers.
     */
    private boolean waitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            java.util.concurrent.CompletableFuture<Boolean> answer = minecraft.submit(condition::getAsBoolean);
            try {
                long remaining = Math.max(1, deadline - System.nanoTime());
                if (answer.get(Math.min(remaining, QUERY_PATIENCE.toNanos()), TimeUnit.NANOSECONDS)) {
                    return true;
                }
            } catch (java.util.concurrent.TimeoutException e) {
                answer.cancel(false);
                continue;
            } catch (Exception e) {
                throw new IllegalStateException("A smoke test check failed on the game thread", e);
            }
            sleep(Duration.ofMillis(100));
        }
        return false;
    }

    /** Runs {@code task} on the game thread and waits for its result, however long the game is busy. */
    private <T> T onGameThread(Callable<T> task) {
        if (minecraft.isSameThread()) {
            // Already on it (a waitUntil condition calling a helper): queueing would deadlock.
            try {
                return task.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        try {
            return minecraft
                    .submit(() -> {
                        try {
                            return task.call();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new IllegalStateException("A smoke test step failed on the game thread", e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
