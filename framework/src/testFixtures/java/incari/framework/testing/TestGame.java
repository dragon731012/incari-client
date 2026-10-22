package incari.framework.testing;

import incari.framework.KeyInput;
import incari.framework.Module;
import incari.framework.ModuleSystem;
import incari.framework.config.ConfigStorage;
import incari.framework.event.CameraAngles;
import incari.framework.event.Crosshair;
import incari.framework.event.FireOverlay;
import incari.framework.event.HeldItem;
import incari.framework.event.MovementInput;
import incari.framework.event.Turn;
import incari.framework.game.EffectView;
import incari.framework.game.Game;
import incari.framework.game.GameKey;
import incari.framework.game.GameOption;
import incari.framework.game.GamePart;
import incari.framework.game.Hitboxes;
import incari.framework.game.ItemView;
import incari.framework.game.Perspective;
import incari.framework.game.PlayerView;
import incari.framework.game.Weather;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.Shape;
import incari.framework.hud.Tone;
import incari.framework.hud.WorldCanvas;
import incari.framework.hud.WorldOverlay;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;
import incari.framework.waypoint.Waypoint;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The game's side of the module framework, faked for tests: config files in memory, keys pressed by
 * the test, a canvas that records what's drawn, and the log kept. A feature module's tests build
 * one around the module and drive it as the game would.
 *
 * <pre>{@code
 * TestGame game = new TestGame(new ToggleSprint());
 * game.module().setEnabled(true);
 * MovementInput input = game.movement(false, 1);  // sprint key tapped once
 * }</pre>
 *
 * <p>Its game ({@link #player}, {@link #options}, {@link #parts}) starts outside a world, with the
 * game's default options and no part of its HUD to show. {@link #join()} puts a player in a world
 * called {@code world/Test}, with its waypoints open.
 */
public final class TestGame implements KeyInput, Game {

    /** The resource packs the game has ({@link Game#selectPack}): the built-in two to begin with. */
    public final Set<String> packs = new HashSet<>(Set.of("programmer_art", "high_contrast"));

    /** The resource packs that are on ({@link Game#packSelected}); none to begin with. */
    public final Set<String> selectedPacks = new HashSet<>();

    /** How many times a pack change reloaded the game's resources. */
    public int reloads;

    /** The game's options, as {@link Game#option} reads them; the game's defaults to begin with. */
    public final Map<GameOption, Double> options = new EnumMap<>(GameOption.class);

    /** The size of each part of the game's HUD there is to show now; none to begin with. */
    public final Map<GamePart, int[]> parts = new EnumMap<>(GamePart.class);

    /** The player, or null outside a world (the start). */
    public @Nullable FakePlayer player;

    /** What {@link Game#samplePlayer()} hands the HUD editor. */
    public final FakePlayer sample = new FakePlayer();

    /** Files by path. */
    public final Map<String, String> files = new HashMap<>();

    /** What the system logged. */
    public final List<String> warnings = new ArrayList<>();

    /** The world the player is in ({@link Game#world()}); null until {@link #join()}. */
    public @Nullable String world;

    /**
     * The client screens modules opened: {@code waypoints}, {@code new <name>}, {@code edit <name>},
     * {@code worldmap}, {@code ask:<title>}.
     */
    public final List<String> screens = new ArrayList<>();

    /** What the last {@link Game#rememberMap} said. */
    public boolean rememberMap;

    /** The game's keybinds held down ({@link Game#isDown}). */
    public final Set<GameKey> keysDown = EnumSet.noneOf(GameKey.class);

    /** How charged the next attack is ({@link Game#attackStrength}). */
    public float attackStrength = 1;

    /** Whether the crosshair is on an entity in reach ({@link Game#onTarget}). */
    public boolean onTarget;

    /** Where the camera is ({@link Game#perspective}). */
    public Perspective perspective = Perspective.FIRST_PERSON;

    /** The sky a module last asked for ({@link Game#overrideSky}): null for the world's. */
    public @Nullable Weather weather;

    /** The time of day a module last asked for; -1 for the world's. */
    public int timeOfDay = -1;

    /**
     * Whether the game's own Sprint: Toggle is on: {@link #movement}'s {@code sprintHeld} is then the
     * game's latch.
     */
    public boolean gameTogglesSprint;

    /** Whether the game's own Sneak: Toggle is on, as {@link #gameTogglesSprint}. */
    public boolean gameTogglesSneak;

    /** Frames a second ({@link Game#fps}). */
    public int fps = 60;

    /** The clock {@link Game#millis} reads; the test moves it. */
    public long millis = 1_000_000L;

    private final ModuleSystem system;
    private final Map<Key, Integer> presses = new HashMap<>();
    private final Set<Key> held = new HashSet<>();
    private final MovementInput movement = new MovementInput();
    private final FireOverlay fire = new FireOverlay();

    /** A module system with these modules, loaded from empty storage. */
    public TestGame(Module... modules) {
        this.system = new ModuleSystem(
                List.of(modules),
                new Storage(),
                (message, error) -> warnings.add(message + (error == null ? "" : ": " + error)),
                this);
        system.load();
    }

    /** The system. */
    public ModuleSystem system() {
        return system;
    }

    /** The first module. */
    public Module module() {
        return system.modules().get(0);
    }

    /** Presses {@code key} once before the next tick. */
    public void press(Key key) {
        presses.merge(key, 1, Integer::sum);
    }

    /** Holds {@code key} down, or lets it go. */
    public void hold(Key key, boolean down) {
        if (down) {
            held.add(key);
        } else {
            held.remove(key);
        }
    }

    /** One client tick. */
    public void tick() {
        system.tick(this);
    }

    /** Runs {@code count} ticks. */
    public void ticks(int count) {
        for (int i = 0; i < count; i++) {
            tick();
        }
    }

    /**
     * Fires the movement event as the game would for a player walking forward.
     *
     * @param sprintHeld whether the sprint key is down
     * @param sprintPresses presses of the sprint key since the last tick
     * @return the input after the listeners, reused by the next call
     */
    public MovementInput movement(boolean sprintHeld, int sprintPresses) {
        return movement(sprintHeld, sprintPresses, false, 0);
    }

    /** {@link #movement(boolean, int)}, with the sneak key too. */
    public MovementInput movement(boolean sprintHeld, int sprintPresses, boolean sneakHeld, int sneakPresses) {
        movement.reset(true, false, false, false, false, sneakHeld, sprintHeld, sneakPresses, sprintPresses);
        movement.gameToggles(gameTogglesSneak, gameTogglesSprint);
        system.events().fireMovement(movement);
        return movement;
    }

    /**
     * Puts the player in a world, {@code world/Test}, with nothing on, opens its waypoints as the
     * client does, fires the join event, and returns the player to dress.
     */
    public FakePlayer join() {
        FakePlayer joined = new FakePlayer();
        player = joined;
        world = "world/Test";
        system.waypoints().open(world);
        system.events().fireJoin("");
        return joined;
    }

    /** Takes the player out of the world, as the client does when they leave it. */
    public void leave() {
        system.events().fireLeave();
        system.waypoints().close();
        player = null;
        world = null;
    }

    /**
     * Fires the fire overlay event as the game would for a frame with the player burning.
     *
     * @return how far down the listeners moved the flames, in flame heights
     */
    public float fireOverlay() {
        fire.reset();
        return system.events().fireFireOverlay(fire);
    }

    /**
     * Fires the crosshair event as the game does each frame, on a recording canvas 320 by 240 at a
     * GUI scale of 2, and returns the canvas.
     */
    public Canvas crosshair() {
        Canvas canvas = new Canvas(320, 240);
        Crosshair crosshair = new Crosshair();
        crosshair.reset(canvas, 160, 120, 0.5F, attackStrength, onTarget);
        system.events().fireCrosshair(crosshair);
        return canvas;
    }

    /** Fires the turn event for the mouse turning {@code yaw} right and {@code pitch} down. */
    public Turn turn(float yaw, float pitch) {
        Turn turn = new Turn();
        turn.reset(yaw, pitch);
        system.events().fireTurn(turn);
        return turn;
    }

    /** Fires the camera event for a player facing {@code yaw}, {@code pitch}, and returns the angles. */
    public CameraAngles camera(float yaw, float pitch) {
        CameraAngles camera = new CameraAngles();
        camera.reset(yaw, pitch);
        system.events().fireCamera(camera);
        return camera;
    }

    /** Fires the container tooltip event, with Shift held or not, and returns what listeners said. */
    public incari.framework.event.ContainerTooltip containerTooltip(boolean shiftDown) {
        incari.framework.event.ContainerTooltip tooltip = new incari.framework.event.ContainerTooltip();
        tooltip.reset(shiftDown);
        system.events().fireContainerTooltip(tooltip);
        return tooltip;
    }

    /** Fires the held item event for one hand's item and returns what the listeners said. */
    public HeldItem heldItem(boolean mainHand, boolean shield, boolean using) {
        HeldItem item = new HeldItem();
        item.reset(mainHand, shield, using);
        system.events().fireHeldItem(item);
        return item;
    }

    /** Fires the held item event for a sword in one hand and returns what the listeners said. */
    public HeldItem heldSword(boolean mainHand, boolean using) {
        HeldItem item = new HeldItem();
        item.reset(mainHand, false, true, using);
        system.events().fireHeldItem(item);
        return item;
    }

    /** The font a module last applied. */
    public incari.framework.game.UiFont font = incari.framework.game.UiFont.RED_HAT_DISPLAY;

    @Override
    public void applyFont(incari.framework.game.UiFont font) {
        this.font = font;
    }

    /** The theme a module last applied; null for Frost. */
    public incari.framework.game.@Nullable ThemeColors theme;

    /** The clipboard's text. */
    public String clipboard = "";

    @Override
    public void applyTheme(incari.framework.game.@Nullable ThemeColors theme) {
        this.theme = theme;
    }

    @Override
    public String clipboard() {
        return clipboard;
    }

    @Override
    public void copy(String text) {
        clipboard = text;
    }

    /** The messages modules showed, each "title: message". */
    public final List<String> messages = new ArrayList<>();

    /**
     * What the player types when a module asks for text ({@link Game#askText}); null to cancel. The
     * field's initial text is entered when this is empty.
     */
    public @Nullable String typed = "";

    @Override
    public void showMessage(String title, String message) {
        messages.add(title + ": " + message);
    }

    @Override
    public void askText(String title, String prompt, String initial, java.util.function.Consumer<String> entered) {
        screens.add("ask:" + title);
        String text = typed;
        if (text != null) {
            entered.accept(text.isEmpty() ? initial : text);
        }
    }

    /** The screenshots modules put on the clipboard. */
    public final List<String> copied = new ArrayList<>();

    /** Whether {@link Game#copyScreenshot} works, as on the browser. */
    public boolean canCopyImages = true;

    @Override
    public void openScreenshots() {
        screens.add("screenshots");
    }

    @Override
    public boolean copyScreenshot(String fileName) {
        if (canCopyImages) {
            copied.add(fileName);
        }
        return canCopyImages;
    }

    /** Fires the screenshot event as the game does once {@code fileName} is saved. */
    public void screenshot(String fileName) {
        system.events().fireScreenshot(fileName);
    }

    @Override
    public float attackStrength() {
        return attackStrength;
    }

    @Override
    public boolean onTarget() {
        return onTarget;
    }

    @Override
    public Perspective perspective() {
        return perspective;
    }

    @Override
    public void setPerspective(Perspective perspective) {
        this.perspective = perspective;
    }

    @Override
    public void overrideSky(@Nullable Weather weather, int timeOfDay) {
        this.weather = weather;
        this.timeOfDay = timeOfDay;
    }

    /** The brightness a module last asked for ({@link Game#overrideBrightness}): 0 for the game's. */
    public float brightness;

    /** The block outline a module last asked for ({@link Game#overrideBlockOutline}): 0 for the game's. */
    public int outlineColor;

    /** Its width; 0 for the game's. */
    public float outlineWidth;

    /** Its fill; 0 for none. */
    public int outlineFill;

    /** The hitboxes a module last asked for ({@link Game#overrideHitboxes}); null for none. */
    public @Nullable Hitboxes hitboxes;

    /** The hurt tint a module last asked for, and how strong in percent; strength 0 for the game's. */
    public int hitColor;

    public int hitStrength;

    /** The glint color a module last asked for; -1 for the game's. */
    public int glintColor = -1;

    /** The field of view a module last asked for, as a share of the game's ({@link Game#overrideFov}). */
    public float fovScale = 1;

    /** Whether a module holds the cinematic camera on ({@link Game#overrideSmoothCamera}). */
    public boolean smoothCamera;

    /** The saturation tint a module last asked for ({@link Game#overrideSaturation}); -1 for none. */
    public int saturationColor = -1;

    /** The effects shown on the target, oldest first ({@link Game#effectOnTarget}). */
    public final java.util.List<incari.framework.game.Effect> effects = new java.util.ArrayList<>();

    /** How many particles the last effect asked for, and whether with its sound. */
    public int effectCount;

    public boolean effectSound;

    @Override
    public void overrideBrightness(float level) {
        brightness = level;
    }

    @Override
    public void overrideBlockOutline(int argb, float width, int fillArgb) {
        outlineColor = argb;
        outlineWidth = width;
        outlineFill = fillArgb;
    }

    @Override
    public void overrideHitboxes(@Nullable Hitboxes hitboxes) {
        this.hitboxes = hitboxes;
    }

    @Override
    public void overrideHitColor(int rgb, int strength) {
        hitColor = rgb;
        hitStrength = strength;
    }

    @Override
    public void overrideGlintColor(int rgb) {
        glintColor = rgb;
    }

    @Override
    public void overrideFov(float scale) {
        fovScale = scale;
    }

    @Override
    public void overrideSmoothCamera(boolean on) {
        smoothCamera = on;
    }

    @Override
    public void overrideSaturation(int rgb) {
        saturationColor = rgb;
    }

    @Override
    public void effectOnTarget(incari.framework.game.Effect effect, int count, boolean sound) {
        effects.add(effect);
        effectCount = count;
        effectSound = sound;
    }

    /** Fires the kill event, as the game does when something the player hit dies. */
    public void kill() {
        system.events().fireKill();
    }

    /** Fires the click event as the game does for a press of {@code button} over the world. */
    public void click(int button) {
        system.events().fireClick(button);
    }

    /** Fires the attack event as the game does for a hit {@code reach} blocks away. */
    public void attack(double reach) {
        system.events().fireAttack(reach);
    }

    @Override
    public boolean isDown(GameKey key) {
        return keysDown.contains(key);
    }

    /** The default binds' names: W, A, S, D, Space, Shift, Ctrl, LMB, RMB. */
    @Override
    public String keyName(GameKey key) {
        return switch (key) {
            case FORWARD -> "W";
            case LEFT -> "A";
            case BACK -> "S";
            case RIGHT -> "D";
            case JUMP -> "Space";
            case SNEAK -> "Shift";
            case SPRINT -> "Ctrl";
            case ATTACK -> "LMB";
            case USE -> "RMB";
        };
    }

    @Override
    public int fps() {
        return fps;
    }

    @Override
    public long millis() {
        return millis;
    }

    /** A stack of one, named by its id on the canvas. */
    @Override
    public ItemView item(String itemId) {
        return new FakePlayer.Item(itemId, 1, 0, 0);
    }

    @Override
    public @Nullable PlayerView player() {
        return player;
    }

    @Override
    public PlayerView samplePlayer() {
        return sample;
    }

    @Override
    public double option(GameOption option) {
        Double value = options.get(option);
        return value == null ? option.defaultValue() : value;
    }

    @Override
    public void setOption(GameOption option, double value) {
        options.put(option, value);
    }

    @Override
    public boolean packSelected(String packId) {
        return selectedPacks.contains(packId);
    }

    @Override
    public boolean selectPack(String packId, boolean on) {
        if (!packs.contains(packId)) {
            return !on;
        }
        if (on ? selectedPacks.add(packId) : selectedPacks.remove(packId)) {
            reloads++;
        }
        return true;
    }

    @Override
    public @Nullable String world() {
        return world;
    }

    @Override
    public void openWaypoints() {
        screens.add("waypoints");
    }

    @Override
    public void editWaypoint(Waypoint waypoint, boolean adding) {
        screens.add((adding ? "new " : "edit ") + waypoint.name());
    }

    @Override
    public void openWorldMap() {
        screens.add("worldmap");
    }

    @Override
    public void rememberMap(boolean remember) {
        rememberMap = remember;
    }

    /**
     * Draws a frame's live layer on a recording canvas, 320 by 240, as the client does every frame:
     * the overlay event, then the live HUD elements. The canvas's camera ({@link Canvas#camera}) is
     * the player's eyes, facing north.
     */
    public Canvas drawLive() {
        Canvas canvas = new Canvas(320, 240);
        FakePlayer eyes = player;
        if (eyes != null) {
            canvas.camera(eyes.x(), eyes.y() + 1.62, eyes.z());
        }
        system.events().fireOverlay(canvas);
        system.drawLiveHud(canvas);
        return canvas;
    }

    /** Fires the world event as the client does each frame, and returns what was drawn. */
    public List<String> drawWorld() {
        List<String> drawn = new ArrayList<>();
        FakePlayer eyes = player;
        double cx = eyes == null ? 0 : eyes.x();
        double cy = eyes == null ? 0 : eyes.y() + 1.62;
        double cz = eyes == null ? 0 : eyes.z();
        system.events().fireWorld(new WorldCanvas() {
            @Override
            public void beam(int x, int y, int z, int rgb) {
                drawn.add("beam at " + x + "," + y + "," + z + " #" + Integer.toHexString(rgb));
            }

            @Override
            public double cameraX() {
                return cx;
            }

            @Override
            public double cameraY() {
                return cy;
            }

            @Override
            public double cameraZ() {
                return cz;
            }
        });
        return drawn;
    }

    /** Draws the HUD on a recording canvas, 320 by 240, and returns it. */
    public Canvas drawHud() {
        return drawHud(false);
    }

    /** Draws the HUD as {@link #drawHud()} does, or as the HUD editor would. */
    public Canvas drawHud(boolean editing) {
        Canvas canvas = new Canvas(320, 240);
        canvas.editing = editing;
        canvas.parts = parts;
        system.drawHud(canvas);
        return canvas;
    }

    @Override
    public int presses(KeySetting key) {
        Integer count = presses.remove(key.get());
        return count == null ? 0 : count;
    }

    @Override
    public boolean isDown(KeySetting key) {
        return held.contains(key.get());
    }

    private final class Storage implements ConfigStorage {

        @Override
        public @Nullable String read(String path) {
            return files.get(path);
        }

        @Override
        public void write(String path, String text) {
            files.put(path, text);
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }
    }

    /**
     * A {@link HudCanvas} that writes down what's drawn: one line per call, in screen units. As a
     * {@link WorldOverlay} its camera faces north (−z) and sees ten GUI units a block at any
     * distance: a point ahead lands at the middle plus ten units a block across and up.
     */
    public static final class Canvas implements WorldOverlay {

        /** Each draw, as {@code text "<text>" at x,y <tone or #color>} or {@code fill ...}. */
        public final List<String> drawn = new ArrayList<>();

        /** Whether it stands for the HUD editor's canvas ({@link HudCanvas#editing()}). */
        public boolean editing;

        /** The size of each part of the game's HUD there is to show. */
        public Map<GamePart, int[]> parts = new EnumMap<>(GamePart.class);

        private final int width;
        private final int height;
        private final List<float[]> transforms = new ArrayList<>();
        private double cameraX;
        private double cameraY;
        private double cameraZ;

        /** Puts the camera at {@code x, y, z}, facing north. */
        public void camera(double x, double y, double z) {
            cameraX = x;
            cameraY = y;
            cameraZ = z;
        }

        @Override
        public boolean project(double x, double y, double z, float[] out) {
            double dx = x - cameraX;
            double dy = y - cameraY;
            double dz = z - cameraZ;
            out[0] = (float) (width / 2.0 + dx * 10);
            out[1] = (float) (height / 2.0 - dy * 10);
            out[2] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            return dz < 0;
        }

        @Override
        public double cameraX() {
            return cameraX;
        }

        @Override
        public double cameraY() {
            return cameraY;
        }

        @Override
        public double cameraZ() {
            return cameraZ;
        }

        /** Records {@code shape CIRCLE 8 at x,y #color} (or the tone), the size and place scaled. */
        @Override
        public void shape(Shape shape, float centerX, float centerY, float size, float rotation, Tone tone) {
            drawn.add("shape " + shape + " " + Math.round(size * scale()) + " at " + at(centerX, centerY)
                    + turned(rotation) + " " + tone);
        }

        @Override
        public void shape(Shape shape, float centerX, float centerY, float size, float rotation, int argb) {
            drawn.add("shape " + shape + " " + Math.round(size * scale()) + " at " + at(centerX, centerY)
                    + turned(rotation) + " #" + Integer.toHexString(argb));
        }

        @Override
        public void disc(float centerX, float centerY, float radius, Tone tone) {
            drawn.add("disc r" + Math.round(radius * scale()) + " at " + at(centerX, centerY) + " " + tone);
        }

        @Override
        public void ring(float centerX, float centerY, float radius, float width, Tone tone) {
            drawn.add("ring r" + Math.round(radius * scale()) + " at " + at(centerX, centerY) + " " + tone);
        }

        @Override
        public void disc(float centerX, float centerY, float radius, int argb) {
            drawn.add("disc r" + Math.round(radius * scale()) + " at " + at(centerX, centerY) + " #"
                    + Integer.toHexString(argb));
        }

        @Override
        public void ring(float centerX, float centerY, float radius, float width, int argb) {
            drawn.add("ring r" + Math.round(radius * scale()) + " at " + at(centerX, centerY) + " #"
                    + Integer.toHexString(argb));
        }

        /** Records {@code arc r8 50% at x,y TONE}. */
        @Override
        public void arc(float centerX, float centerY, float radius, float width, float fraction, Tone tone) {
            drawn.add("arc r" + Math.round(radius * scale()) + " " + Math.round(fraction * 100) + "% at "
                    + at(centerX, centerY) + " " + tone);
        }

        /** Records {@code arc r8 50% at x,y #color}. */
        @Override
        public void arc(float centerX, float centerY, float radius, float width, float fraction, int argb) {
            drawn.add("arc r" + Math.round(radius * scale()) + " " + Math.round(fraction * 100) + "% at "
                    + at(centerX, centerY) + " #" + Integer.toHexString(argb));
        }

        /** Records {@code terrain round 96 at x,y around cx,cz 128 blocks facing 0}. */
        @Override
        public void terrain(
                int x,
                int y,
                int size,
                boolean round,
                double centerX,
                double centerZ,
                float blocksAcross,
                float rotation) {
            drawn.add("terrain " + (round ? "round " : "square ") + Math.round(size * scale()) + " at " + at(x, y)
                    + " around " + Math.round(centerX) + "," + Math.round(centerZ) + " " + Math.round(blocksAcross)
                    + " blocks facing " + Math.round(rotation));
        }

        private static String turned(float rotation) {
            return rotation == 0 ? "" : " turned " + Math.round(rotation);
        }

        private float scale() {
            float scale = 1;
            for (float[] t : transforms) {
                scale *= t[2];
            }
            return scale;
        }

        /** A canvas of this many GUI units. */
        public Canvas(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public boolean editing() {
            return editing;
        }

        @Override
        public int screenWidth() {
            return width;
        }

        @Override
        public int screenHeight() {
            return height;
        }

        /** Six units a character. */
        @Override
        public int textWidth(String text) {
            return text.length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public void text(String text, int x, int y, Tone tone) {
            drawn.add("text \"" + text + "\" at " + at(x, y) + " " + tone);
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            drawn.add("text \"" + text + "\" at " + at(x, y) + " #" + Integer.toHexString(argb));
        }

        @Override
        public void fill(int x, int y, int w, int h, Tone tone) {
            drawn.add("fill " + w + "x" + h + " at " + at(x, y) + " " + tone);
        }

        @Override
        public void fill(int x, int y, int w, int h, int argb) {
            drawn.add("fill " + w + "x" + h + " at " + at(x, y) + " #" + Integer.toHexString(argb));
        }

        /** Records {@code item "<name>" at x,y}, with {@code +decorations} if asked for. */
        @Override
        public void item(ItemView item, int x, int y, boolean decorations) {
            if (item instanceof FakePlayer.Item fake) {
                drawn.add("item \"" + fake.name() + "\" at " + at(x, y) + (decorations ? " +decorations" : ""));
            }
        }

        /** Records {@code effect "<name>" at x,y}. */
        @Override
        public void effect(EffectView effect, int x, int y) {
            drawn.add("effect \"" + effect.name() + "\" at " + at(x, y));
        }

        @Override
        public boolean hasPart(GamePart part) {
            return parts.containsKey(part) || editing;
        }

        /** The part's size from {@link #parts}; in the editor with none, a 60 by 40 sample. */
        @Override
        public int partWidth(GamePart part) {
            int[] size = parts.get(part);
            return size != null ? size[0] : editing ? 60 : 0;
        }

        @Override
        public int partHeight(GamePart part) {
            int[] size = parts.get(part);
            return size != null ? size[1] : editing ? 40 : 0;
        }

        /** Records {@code part SCOREBOARD WxH at x,y}, with the scale applied to the size. */
        @Override
        public void part(GamePart part, int x, int y) {
            if (!hasPart(part)) {
                return;
            }
            float scale = scale();
            drawn.add("part " + part + " " + Math.round(partWidth(part) * scale) + "x"
                    + Math.round(partHeight(part) * scale) + " at " + at(x, y));
        }

        @Override
        public void push(float x, float y, float scale) {
            transforms.add(new float[] {x, y, scale});
        }

        @Override
        public void pop() {
            transforms.remove(transforms.size() - 1);
        }

        /** Whether every push was popped. */
        public boolean balanced() {
            return transforms.isEmpty();
        }

        /** Where element coordinates land on the screen, with every push applied. */
        private String at(float x, float y) {
            float sx = x;
            float sy = y;
            for (int i = transforms.size() - 1; i >= 0; i--) {
                float[] t = transforms.get(i);
                sx = t[0] + sx * t[2];
                sy = t[1] + sy * t[2];
            }
            return Math.round(sx) + "," + Math.round(sy);
        }
    }
}
