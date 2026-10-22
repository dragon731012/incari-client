package incari.framework.game;

/**
 * How the client draws entities' hitboxes ({@link Game#overrideHitboxes}): the box each entity is hit
 * by, in the world, on this client only. Colors are {@code 0xAARRGGBB}.
 *
 * @param color the boxes' color
 * @param targetColor the box of the entity the crosshair is on; the same as {@code color} for no
 *     difference
 * @param width the lines' width in screen pixels, as the block outline's
 * @param show which entities get a box
 * @param eyeLine a line round the box at the entity's eye height
 * @param lookLine an arrow from the entity's eyes the way it looks
 * @param own the player's own box too, in third person
 */
public record Hitboxes(
        int color, int targetColor, float width, Show show, boolean eyeLine, boolean lookLine, boolean own) {

    /** Which entities get a box. */
    public enum Show {
        /** Other players only. */
        PLAYERS("Players"),
        /** Players and mobs: anything with health. */
        LIVING("Players and mobs"),
        /** Every entity the game draws: items, arrows, boats and the rest too. */
        ALL("Everything");

        private final String label;

        Show(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }
}
