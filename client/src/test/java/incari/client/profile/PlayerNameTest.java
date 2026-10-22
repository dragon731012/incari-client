package incari.client.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerNameTest {

    @TempDir
    Path gameDirectory;

    @AfterEach
    void forget() {
        PlayerName.reset();
    }

    private static User launched(String name, String token) {
        return new User(name, UUIDUtil.createOfflinePlayerUUID(name), token, Optional.empty(), Optional.empty());
    }

    private void save(String name) throws IOException {
        Files.createDirectories(gameDirectory.resolve("incari"));
        Files.writeString(gameDirectory.resolve("incari/profile.properties"), "name=" + name + "\n");
    }

    @Test
    void takesWhatEveryServerTakes() {
        assertThat(PlayerName.isValid("Steve_123")).isTrue();
        assertThat(PlayerName.isValid("abc")).isTrue();
        assertThat(PlayerName.isValid("abcdefghijklmnop")).isTrue();
        assertThat(PlayerName.isValid("ab")).isFalse();
        assertThat(PlayerName.isValid("abcdefghijklmnopq")).isFalse();
        assertThat(PlayerName.isValid("two words")).isFalse();
        assertThat(PlayerName.isValid("ümlaut")).isFalse();
    }

    @Test
    void filterKeepsLettersDigitsAndUnderscores() {
        assertThat(PlayerName.filter("Hi there! _9-é")).isEqualTo("Hithere_9");
    }

    @Test
    void offlinePlaysUnderTheSavedNameWithItsOfflineUuid() throws IOException {
        save("Chosen_1");
        User user = PlayerName.user(launched("Player123", "0"), gameDirectory.toFile());
        assertThat(user.getName()).isEqualTo("Chosen_1");
        assertThat(user.getProfileId()).isEqualTo(UUIDUtil.createOfflinePlayerUUID("Chosen_1"));
        assertThat(PlayerName.asksAtStartup()).isTrue();
    }

    @Test
    void withNothingSavedPlaysUnderTheLaunchName() {
        User launched = launched("Player123", "0");
        assertThat(PlayerName.user(launched, gameDirectory.toFile())).isSameAs(launched);
    }

    @Test
    void aNameFixedAtLaunchStands() throws IOException {
        save("Chosen_1");
        PlayerName.fixAtLaunch();
        User launched = launched("Bench", "0");
        assertThat(PlayerName.user(launched, gameDirectory.toFile())).isSameAs(launched);
        assertThat(PlayerName.asksAtStartup()).isFalse();
    }

    @Test
    void anAccountKeepsItsOwnName() throws IOException {
        save("Chosen_1");
        User account = new User("Real", UUID.randomUUID(), "token", Optional.empty(), Optional.empty());
        assertThat(PlayerName.user(account, gameDirectory.toFile())).isSameAs(account);
        assertThat(PlayerName.editable()).isFalse();
        assertThat(PlayerName.asksAtStartup()).isFalse();
    }

    @Test
    void setPlaysUnderTheNameAndKeepsIt() throws IOException {
        PlayerName.user(launched("Player123", "0"), gameDirectory.toFile());
        PlayerName.set("New_Name");
        assertThat(PlayerName.name()).isEqualTo("New_Name");
        assertThat(Files.readString(gameDirectory.resolve("incari/profile.properties")))
                .contains("name=New_Name");

        PlayerName.reset();
        assertThat(PlayerName.user(launched("Player456", "0"), gameDirectory.toFile())
                        .getName())
                .isEqualTo("New_Name");
    }

    @Test
    void refusesANameNoServerTakes() {
        PlayerName.user(launched("Player123", "0"), gameDirectory.toFile());
        assertThatThrownBy(() -> PlayerName.set("no")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ignoresASavedNameNoServerTakes() throws IOException {
        save("bad name!");
        User launched = launched("Player123", "0");
        assertThat(PlayerName.user(launched, gameDirectory.toFile())).isSameAs(launched);
    }

    @Test
    void suggestsNamesWithIncariThatEveryServerTakes() {
        Random random = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            String name = PlayerName.suggest(random);
            assertThat(name).contains("Incari");
            assertThat(PlayerName.isValid(name)).as(name).isTrue();
            assertThat(PlayerName.isSuggestion(name)).as(name).isTrue();
        }
    }

    @Test
    void tellsMadeUpNamesFromChosenOnes() {
        assertThat(PlayerName.isSuggestion("IncariFox482")).isTrue();
        assertThat(PlayerName.isSuggestion("YeeIncari07")).isFalse();
        assertThat(PlayerName.isSuggestion("YeeIncari70")).isTrue();
        assertThat(PlayerName.isSuggestion("IncariFox")).isFalse();
        assertThat(PlayerName.isSuggestion("IncariFox1")).isFalse();
        assertThat(PlayerName.isSuggestion("Steve_123")).isFalse();
        assertThat(PlayerName.isSuggestion("Incari123")).isFalse();
    }
}
