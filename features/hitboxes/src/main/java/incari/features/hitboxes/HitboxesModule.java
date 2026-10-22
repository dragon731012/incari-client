package incari.features.hitboxes;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.game.Hitboxes;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.FloatSetting;
import incari.framework.setting.IntSetting;
import incari.framework.setting.Setting;

/**
 * Hitboxes: the box each entity is hit by, in the player's color and width, the one under the
 * crosshair in another if they like; with the eye-height line and look arrow F3+B shows. The style
 * is handed to the game once per change, not per tick: the client draws it every frame.
 */
public final class HitboxesModule extends Module {

    final ColorSetting color = settings().color("color", "Color", 0xFFFFFF).describedAs("The boxes' color.");
    final IntSetting opacity = settings()
            .integer("opacity", "Opacity", 10, 100, 100)
            .formattedAs(value -> value + "%")
            .describedAs("How solid the boxes are.");
    final FloatSetting width =
            settings().decimal("width", "Width", 1, 6, 0.5F, 2).describedAs("How thick the lines are, in pixels.");
    final BooleanSetting targetColored = settings()
            .bool("target-colored", "Target color", true)
            .describedAs("Colors the box your crosshair is on differently.");
    final ColorSetting targetColor = settings()
            .color("target-color", "Target's color", 0xFF5555)
            .describedAs("The color of the box your crosshair is on.")
            .visibleWhen(targetColored::get);
    final ChoiceSetting<Hitboxes.Show> show = settings()
            .choice("show", "Show", Hitboxes.Show.values(), Hitboxes.Show.LIVING, Hitboxes.Show::label)
            .describedAs("Which entities get a box.");
    final BooleanSetting eyeLine =
            settings().bool("eye-line", "Eye height", true).describedAs("A red line where each one's eyes are.");
    final BooleanSetting lookLine =
            settings().bool("look-line", "Look direction", true).describedAs("A blue arrow the way each one looks.");
    final BooleanSetting own =
            settings().bool("own", "Your own", false).describedAs("Your own box too, in third person.");

    /** Hitboxes, off until the player turns it on. */
    public HitboxesModule() {
        super("hitboxes", "Hitboxes", "Shows the box each player or mob is hit by.", Category.COMBAT);
        for (Setting<?> setting : settings()) {
            setting.onChange(this::changed);
        }
    }

    private void changed() {
        if (isEnabled()) {
            system().game().overrideHitboxes(style());
        }
    }

    @Override
    protected void onEnable() {
        system().game().overrideHitboxes(style());
    }

    @Override
    protected void onDisable() {
        system().game().overrideHitboxes(null);
    }

    /** What the settings say, as the game takes it. */
    Hitboxes style() {
        int argb = withOpacity(color.get(), opacity.get());
        int target = targetColored.get() ? withOpacity(targetColor.get(), opacity.get()) : argb;
        return new Hitboxes(argb, target, width.get(), show.get(), eyeLine.get(), lookLine.get(), own.get());
    }

    /** {@code rgb} at {@code percent} opacity. */
    static int withOpacity(int rgb, int percent) {
        return Math.round(percent * 255 / 100.0F) << 24 | (rgb & 0xFFFFFF);
    }
}
