package incari.client.modules;

import incari.framework.game.Hitboxes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The hitboxes a module holds ({@code Game.overrideHitboxes}): each entity's box, drawn as the
 * game's own debug shapes (gizmos), so they sort and fade with the world's. Render thread, while the
 * frame's gizmos are collected. While no module holds them, the hook is a field read.
 */
public final class ModuleHitboxes {

    /** The eye-height line's and the look arrow's colors, as F3+B draws them (the box's alpha added). */
    private static final int EYE_RGB = 0xFF0000;

    private static final int LOOK_RGB = 0x0000FF;

    /** How far the look arrow reaches, in blocks. */
    private static final double LOOK_LENGTH = 2.0;

    /** The game's debug line width at 1080 lines; the module's widths are scaled from it alike. */
    private static final float GAME_LINE_WIDTH = 2.5F;

    private static @Nullable Hitboxes held;

    private ModuleHitboxes() {}

    /** {@code Game.overrideHitboxes}'s. */
    static void hold(@Nullable Hitboxes hitboxes) {
        held = hitboxes;
    }

    /** What's drawn from the next frame; null for nothing. */
    public static @Nullable Hitboxes held() {
        return held;
    }

    /**
     * Hook ({@code LevelExtractor}, after the debug renderer's gizmos): the held boxes of every entity
     * in view. Not while F3+B shows the game's own, which say more (the server's boxes, in
     * singleplayer).
     */
    @SuppressWarnings("ReferenceEquality") // the camera's and the aimed-at entity objects, not equal ones
    public static void emit(Frustum frustum, double camX, double camY, double camZ) {
        Hitboxes style = held;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (style == null
                || level == null
                || minecraft.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES)) {
            return;
        }
        float width = style.width() * Math.max(1.0F, minecraft.getWindow().getAppropriateLineWidth() / GAME_LINE_WIDTH);
        GizmoStyle box = GizmoStyle.stroke(style.color(), width);
        GizmoStyle target = GizmoStyle.stroke(style.targetColor(), width);
        int alpha = ARGB.alpha(style.color());
        int eye = ARGB.color(alpha, EYE_RGB);
        int look = ARGB.color(alpha, LOOK_RGB);
        Entity camera = minecraft.getCameraEntity();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        Entity aimed = minecraft.crosshairPickEntity;
        long fade = Util.toMillis(minecraft.options.chunkSectionFadeInTime().get());

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isInvisible() || !shown(style.show(), entity)) {
                continue;
            }
            if (entity == camera && (firstPerson || !style.own())) {
                continue;
            }
            float partial = minecraft
                    .getDeltaTracker()
                    .getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
            if (!minecraft.levelExtractor.isEntityVisible(entity, frustum, camX, camY, camZ, partial, fade)) {
                continue;
            }
            // The box where the entity is drawn this frame, between its last two ticks' places.
            double dx = Mth.lerp(partial, entity.xo, entity.getX()) - entity.getX();
            double dy = Mth.lerp(partial, entity.yo, entity.getY()) - entity.getY();
            double dz = Mth.lerp(partial, entity.zo, entity.getZ()) - entity.getZ();
            AABB bounds = entity.getBoundingBox().move(dx, dy, dz);
            Gizmos.cuboid(bounds, entity == aimed ? target : box);
            if (entity instanceof EnderDragon dragon) {
                // The dragon is hit by its parts, not its own box.
                for (EnderDragonPart part : dragon.getSubEntities()) {
                    Gizmos.cuboid(part.getBoundingBox().move(dx, dy, dz), box);
                }
            }
            if (!(entity instanceof LivingEntity)) {
                continue;
            }
            double eyeY = bounds.minY + entity.getEyeHeight();
            if (style.eyeLine()) {
                Vec3 a = new Vec3(bounds.minX, eyeY, bounds.minZ);
                Vec3 b = new Vec3(bounds.maxX, eyeY, bounds.minZ);
                Vec3 c = new Vec3(bounds.maxX, eyeY, bounds.maxZ);
                Vec3 d = new Vec3(bounds.minX, eyeY, bounds.maxZ);
                Gizmos.line(a, b, eye, width);
                Gizmos.line(b, c, eye, width);
                Gizmos.line(c, d, eye, width);
                Gizmos.line(d, a, eye, width);
            }
            if (style.lookLine()) {
                Vec3 from = new Vec3((bounds.minX + bounds.maxX) / 2, eyeY, (bounds.minZ + bounds.maxZ) / 2);
                Gizmos.arrow(from, from.add(entity.getViewVector(partial).scale(LOOK_LENGTH)), look, width);
            }
        }
    }

    private static boolean shown(Hitboxes.Show show, Entity entity) {
        return switch (show) {
            case PLAYERS -> entity instanceof Player;
            case LIVING -> entity instanceof LivingEntity;
            case ALL -> true;
        };
    }
}
