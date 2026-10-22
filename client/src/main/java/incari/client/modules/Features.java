package incari.client.modules;

import incari.features.armorpotionview.ArmorPotionView;
import incari.features.blockoutline.BlockOutline;
import incari.features.clearchat.ClearChat;
import incari.features.cooldownindicator.CooldownIndicator;
import incari.features.coordinates.Coordinates;
import incari.features.cps.Cps;
import incari.features.crosshair.CrosshairEditor;
import incari.features.customscoreboard.CustomScoreboard;
import incari.features.fpsdisplay.FpsDisplay;
import incari.features.freelook.Freelook;
import incari.features.fullbright.Fullbright;
import incari.features.glintcolor.EnchantGlint;
import incari.features.hitboxes.HitboxesModule;
import incari.features.hitcolor.HitColor;
import incari.features.hiteffects.HitEffects;
import incari.features.keystrokes.Keystrokes;
import incari.features.lowfire.LowFire;
import incari.features.lowshield.LowShield;
import incari.features.minimap.Minimap;
import incari.features.nohurtcam.NoHurtCam;
import incari.features.oldanimations.OldAnimations;
import incari.features.oldtextures.OldTextures;
import incari.features.reachdisplay.ReachDisplay;
import incari.features.saturation.Saturation;
import incari.features.shieldtotem.ShieldTotem;
import incari.features.shortswords.ShortSwords;
import incari.features.shulkertooltips.ShulkerTooltips;
import incari.features.theme.ThemeEditor;
import incari.features.togglesprint.ToggleSprint;
import incari.features.waypoints.WaypointsModule;
import incari.features.weatherchanger.WeatherChanger;
import incari.features.zoom.Zoom;
import incari.framework.Module;
import java.util.List;

/**
 * Every feature module in the build, in the order the module menu lists them within a category. A
 * new feature is a {@code features/<name>} project, a line in {@code :client}'s dependencies, and a
 * line here: no reflection or service loading, which the browser build's closed world can't see.
 */
final class Features {

    private Features() {}

    /** A new instance of each. */
    static List<Module> all() {
        return List.of(
                new ToggleSprint(),
                new Keystrokes(),
                new Cps(),
                new FpsDisplay(),
                new ReachDisplay(),
                new Coordinates(),
                new ArmorPotionView(),
                new ShieldTotem(),
                new Saturation(),
                new CustomScoreboard(),
                new Minimap(),
                new CooldownIndicator(),
                new HitEffects(),
                new HitboxesModule(),
                new OldAnimations(),
                new Freelook(),
                new Zoom(),
                new CrosshairEditor(),
                new LowShield(),
                new ShortSwords(),
                new WeatherChanger(),
                new Fullbright(),
                new BlockOutline(),
                new HitColor(),
                new EnchantGlint(),
                new OldTextures(),
                new ThemeEditor(),
                new ClearChat(),
                new LowFire(),
                new NoHurtCam(),
                new WaypointsModule(),
                new ShulkerTooltips());
    }
}
