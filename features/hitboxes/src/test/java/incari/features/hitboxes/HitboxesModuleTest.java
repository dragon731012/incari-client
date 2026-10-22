package incari.features.hitboxes;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.game.Hitboxes;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class HitboxesModuleTest {

    private final HitboxesModule module = new HitboxesModule();
    private final TestGame game = new TestGame(module);

    @Test
    void drawsTheBoxesWhileOnAndNoneAfter() {
        assertThat(game.hitboxes).isNull();

        module.setEnabled(true);
        assertThat(game.hitboxes)
                .isEqualTo(new Hitboxes(0xFFFFFFFF, 0xFFFF5555, 2, Hitboxes.Show.LIVING, true, true, false));

        module.setEnabled(false);
        assertThat(game.hitboxes).isNull();
    }

    @Test
    void aChangedSettingReachesTheGameAtOnceButOnlyWhileOn() {
        module.setEnabled(true);
        module.color.set(0x00FF00);
        module.opacity.set(50);
        module.show.set(Hitboxes.Show.PLAYERS);
        assertThat(game.hitboxes).isNotNull();
        assertThat(game.hitboxes.color()).isEqualTo(0x8000FF00);
        assertThat(game.hitboxes.show()).isEqualTo(Hitboxes.Show.PLAYERS);

        module.targetColored.set(false);
        assertThat(game.hitboxes.targetColor()).isEqualTo(game.hitboxes.color());

        module.setEnabled(false);
        module.width.set(4);
        assertThat(game.hitboxes).isNull();
    }

    @Test
    void opacityScalesTheAlpha() {
        assertThat(HitboxesModule.withOpacity(0xFF123456, 100)).isEqualTo(0xFF123456);
        assertThat(HitboxesModule.withOpacity(0x123456, 10) >>> 24).isEqualTo(26);
    }
}
