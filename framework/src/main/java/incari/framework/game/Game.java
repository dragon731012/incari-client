package incari.framework.game;

import incari.framework.waypoint.Waypoint;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The game as modules see it: the local player, the world they're in, the options a module may
 * hold ({@link incari.framework.Module#holdOption}) and the client's screens a module may open. The
 * client fills it in; {@link #NONE} stands in where there's no game. Game and render thread (the
 * same thread in Incari's builds).
 */
public interface Game {

    /** No player and no options to hold: every read is empty, every write dropped. */
    Game NONE = new Game() {
        @Override
        public @Nullable PlayerView player() {
            return null;
        }

        @Override
        public PlayerView samplePlayer() {
            return PlayerView.EMPTY;
        }

        @Override
        public double option(GameOption option) {
            return option.defaultValue();
        }

        @Override
        public void setOption(GameOption option, double value) {}

        @Override
        public boolean packSelected(String packId) {
            return false;
        }

        @Override
        public boolean selectPack(String packId, boolean on) {
            return false;
        }

        @Override
        public @Nullable String world() {
            return null;
        }

        @Override
        public void openWaypoints() {}

        @Override
        public void editWaypoint(Waypoint waypoint, boolean adding) {}

        @Override
        public void openWorldMap() {}

        @Override
        public void rememberMap(boolean remember) {}

        @Override
        public boolean isDown(GameKey key) {
            return false;
        }

        @Override
        public String keyName(GameKey key) {
            return "";
        }

        @Override
        public int fps() {
            return 0;
        }

        @Override
        public ItemView item(String itemId) {
            return PlayerView.NO_ITEM;
        }

        @Override
        public float attackStrength() {
            return 1;
        }

        @Override
        public boolean onTarget() {
            return false;
        }

        @Override
        public Perspective perspective() {
            return Perspective.FIRST_PERSON;
        }

        @Override
        public void setPerspective(Perspective perspective) {}

        @Override
        public void overrideSky(@Nullable Weather weather, int timeOfDay) {}

        @Override
        public void applyTheme(@Nullable ThemeColors theme) {}

        @Override
        public void applyFont(UiFont font) {}

        @Override
        public String clipboard() {
            return "";
        }

        @Override
        public void copy(String text) {}

        @Override
        public void showMessage(String title, String message) {}

        @Override
        public void askText(String title, String prompt, String initial, Consumer<String> entered) {}

        @Override
        public void openScreenshots() {}

        @Override
        public boolean copyScreenshot(String fileName) {
            return false;
        }

        @Override
        public void overrideBlockOutline(int argb, float width, int fillArgb) {}

        @Override
        public void overrideHitboxes(@Nullable Hitboxes hitboxes) {}

        @Override
        public void overrideBrightness(float level) {}

        @Override
        public void overrideHitColor(int rgb, int strength) {}

        @Override
        public void overrideGlintColor(int rgb) {}

        @Override
        public void overrideFov(float scale) {}

        @Override
        public void overrideSmoothCamera(boolean on) {}

        @Override
        public void overrideSaturation(int rgb) {}

        @Override
        public void effectOnTarget(Effect effect, int count, boolean sound) {}
    };

    /**
     * The local player, or null outside a world. The view is reused: read it during the call that
     * asked for it, and keep no reference to what it hands out.
     */
    @Nullable
    PlayerView player();

    /**
     * A made-up player in diamond armor with a couple of effects, for the HUD editor to show where
     * an element goes while the real player has nothing to show.
     */
    PlayerView samplePlayer();

    /** {@code option}'s value now, in the game's options. */
    double option(GameOption option);

    /** Sets {@code option} in the game's options and saves them, as its slider would. */
    void setOption(GameOption option, double value);

    /**
     * Whether the resource pack {@code packId} ({@code programmer_art}) is on, as the game's
     * Resource Packs screen last left it.
     */
    boolean packSelected(String packId);

    /**
     * Turns the resource pack {@code packId} on (on top of the others) or off, as the Resource
     * Packs screen would: the game's options are saved and its resources reload if that changed
     * anything. The reload takes a moment; {@link #packSelected} says so at once.
     *
     * @return whether the pack is now as asked: false if the game has no such pack
     */
    boolean selectPack(String packId, boolean on);

    /**
     * Which world the player is in, as a name to keep things by: {@code server/<address>} on a
     * server, {@code world/<folder>} in singleplayer; null outside a world. The same world gives the
     * same name each time it's joined.
     */
    @Nullable
    String world();

    /** Opens the waypoint list over the game ({@link incari.framework.ModuleSystem#waypoints()}). */
    void openWaypoints();

    /**
     * Opens the waypoint editor over the game for {@code waypoint}: a new one the player may still
     * cancel ({@code adding}; added only if they save it), or one of the world's to change.
     */
    void editWaypoint(Waypoint waypoint, boolean adding);

    /**
     * Opens the world map over the game: the land the map has seen, to drag about and zoom, with
     * the player and the waypoints on it.
     */
    void openWorldMap();

    /**
     * Whether what the map sees is saved per world and dimension, so the world map shows land the
     * player has left, even on a later day. Off, it shows what the map saw since joining, while it
     * has room. The minimap's setting.
     */
    void rememberMap(boolean remember);

    /**
     * Whether the player holds {@code key} down, as the game reads its keybinds: false while a
     * screen is open.
     */
    boolean isDown(GameKey key);

    /** What {@code key} is bound to, short, as the controls screen writes it ("W", "LMB"). */
    String keyName(GameKey key);

    /** Frames drawn in the last second, as the debug screen counts them. */
    int fps();

    /**
     * Milliseconds on a clock that only goes forward, for timing what happens between ticks (clicks
     * a second). Not the time of day.
     */
    default long millis() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * One of the item with this id ({@code minecraft:totem_of_undying}), to draw its icon; {@link
     * ItemView#isEmpty()} if there's no such item. The same view for the same id.
     */
    ItemView item(String itemId);

    /** How charged the player's next attack is, 0 just swung to 1 ready (1.9's cooldown); 1 outside a world. */
    float attackStrength();

    /** Whether the crosshair is on a living entity the player can reach. */
    boolean onTarget();

    /** Where the camera is (F5). */
    Perspective perspective();

    /** Moves the camera, as F5 would. */
    void setPerspective(Perspective perspective);

    /**
     * Shows the sky as the player chose, on this client only: {@code weather} (null for the
     * world's) and {@code timeOfDay} in ticks from dawn, 0 to 23999 (6000 noon, 18000 midnight; -1
     * for the world's). Clearing both brings the world's back. The holding module calls it every
     * tick; the client restores the server's weather after.
     */
    void overrideSky(@Nullable Weather weather, int timeOfDay);

    /** Opens the screenshot gallery over whatever's shown. */
    void openScreenshots();

    /**
     * Puts the screenshot {@code fileName} on the clipboard as an image, without waiting.
     *
     * @return false if this platform can't put images on the clipboard
     */
    boolean copyScreenshot(String fileName);

    /**
     * Draws the outline of the block the crosshair is on in {@code argb}, {@code width} screen
     * pixels wide (0 for the game's width), its faces filled in {@code fillArgb} (0: not filled);
     * {@code argb} 0 gives the game's own back. Held until changed.
     */
    void overrideBlockOutline(int argb, float width, int fillArgb);

    /**
     * Draws entities' hitboxes as {@code hitboxes} says, every frame, whatever the server's debug
     * rules; null draws none. F3+B's own boxes show instead while the player has those on. Held until
     * changed.
     */
    void overrideHitboxes(@Nullable Hitboxes hitboxes);

    /**
     * Lights the world as night vision would, at {@code level} from 0 (the game's own light) to 1
     * (everything fully lit), on this client only; a real night vision effect still shows if it's
     * stronger. Held until changed.
     */
    void overrideBrightness(float level);

    /**
     * Tints hurt entities {@code rgb} instead of red, {@code strength} percent of the way from their
     * own colors to it (the game's red is 30%); a strength of 0 gives the game's own tint back. Held
     * until changed.
     */
    void overrideHitColor(int rgb, int strength);

    /** Colors the enchantment glint {@code rgb} instead of its purple; -1 for the game's own. */
    void overrideGlintColor(int rgb);

    /**
     * Narrows the world's field of view to {@code scale} of the game's (0.25: a quarter, four times
     * zoomed), on this client only; the hand and the HUD keep theirs. 1 gives the game's own back.
     * Held until changed.
     */
    void overrideFov(float scale);

    /**
     * Turns the game's cinematic camera (smooth turning, F8's) on while held; false gives the player's
     * own back.
     */
    void overrideSmoothCamera(boolean on);

    /**
     * Shows the player's saturation on the hunger bar: the saturated shanks outlined in {@code rgb}. -1 for
     * the game's own bar. Held until changed.
     */
    void overrideSaturation(int rgb);

    /**
     * Shows {@code count} of {@code effect}'s particles on the entity the player last hit, or where
     * it was when it died, with the effect's sound if {@code sound}; on this client only. Nothing if
     * the player hasn't hit anything in this world.
     */
    void effectOnTarget(Effect effect, int count, boolean sound);

    /** Draws the client in {@code theme} from the next frame; null for Frost, its own. */
    void applyTheme(@Nullable ThemeColors theme);

    /**
     * Draws the game's and the client's text in {@code font} from the next frame or two (a new font
     * loads in the background first).
     */
    void applyFont(UiFont font);

    /** The clipboard's text; empty if it holds none (in the browser: the last text pasted). */
    String clipboard();

    /** Puts {@code text} on the clipboard. */
    void copy(String text);

    /** Shows {@code message} under {@code title} for a few seconds, in a corner over what's shown. */
    void showMessage(String title, String message);

    /**
     * Asks the player for a line of text: {@code title} and {@code prompt} above a field holding
     * {@code initial}. {@code entered} gets the text on Done (game thread, field closed), and isn't
     * called on cancel. In the browser this is how a module reads the clipboard: a page only gets
     * clipboard text on a paste (Ctrl+V), and {@link #clipboard()} has only the last one.
     */
    void askText(String title, String prompt, String initial, Consumer<String> entered);
}
