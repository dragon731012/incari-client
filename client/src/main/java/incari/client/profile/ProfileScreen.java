package incari.client.profile;

import com.mojang.blaze3d.Blaze3D;
import incari.client.skins.OwnSkin;
import incari.client.startup.DeferredModels;
import incari.client.ui.Ui;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Edit Profile, as Eagler clients have it: the name the player plays under offline, and the skin
 * they wear, picked from a list (the game's default skins and the PNGs they added) beside a model
 * wearing it that they can turn. It opens at start-up, before the title screen, unless the entry
 * point named the player ({@link PlayerName#fixAtLaunch}); the title screen's link and the skin
 * customisation screen open it again. Capes have a screen of their own ({@link CapeScreen}).
 *
 * <p>In a world the name can't change: the server knows the player by it until they leave.
 *
 * <p>English only for now; Incari's screens don't have language files yet.
 */
public final class ProfileScreen extends Screen {

    private static final Component USERNAME = Component.literal("Username");
    private static final Component PLAYER_SKIN = Component.literal("Player Skin");
    private static final Component NAME_RULES = Component.literal("A name is 3 to 16 letters, digits or underscores")
            .withStyle(ChatFormatting.RED);
    private static final int LABEL = 0xFFA0A0A0;
    private static final int PREVIEW_WIDTH = 56;
    private static final int PREVIEW_HEIGHT = 100;
    private static final int CONTROLS_WIDTH = 160;
    private static final int GAP = 12;

    /** Where Done goes: the next start-up screen, or the screen this one was opened from. */
    private final Runnable next;

    private final OwnSkin skin = OwnSkin.shared();
    private final boolean nameEditable;
    private String name = PlayerName.name();
    private Component message = CommonComponents.EMPTY;

    private @Nullable EditBox nameBox;
    private @Nullable SkinDropdown skins;
    private @Nullable Button done;
    private @Nullable PlayerSkinWidget preview;
    private int previewX;
    private int previewY;

    private ProfileScreen(Runnable next) {
        super(Component.literal("Edit Profile"));
        this.next = next;
        this.nameEditable = PlayerName.editable() && Minecraft.getInstance().level == null;
    }

    /** At start-up, before {@code next} (the title screen). */
    public static ProfileScreen atStartup(Runnable next) {
        return new ProfileScreen(next);
    }

    /** Opened from {@code parent}, which Done goes back to. */
    public static ProfileScreen from(Screen parent) {
        return new ProfileScreen(() -> Minecraft.getInstance().gui.setScreen(parent));
    }

    /**
     * Hook ({@code Gui.addInitialScreens}): this screen among the ones the game shows once loaded,
     * if the player's name is theirs to choose.
     */
    public static void addAtStartup(List<Function<Runnable, Screen>> screens) {
        if (PlayerName.asksAtStartup()) {
            Function<Runnable, Screen> screen = ProfileScreen::atStartup;
            screens.add(screen);
        }
    }

    /** Hook (the skin customisation screen): a button here. */
    public static Button button(Screen parent) {
        return Button.builder(
                        Component.literal("Edit Profile..."),
                        button -> Minecraft.getInstance().gui.setScreen(from(parent)))
                .width(150)
                .build();
    }

    @Override
    protected void init() {
        int left = this.width / 2 - (PREVIEW_WIDTH + GAP + CONTROLS_WIDTH) / 2;
        int top = Math.max(32, this.height / 2 - 76);
        int controls = left + PREVIEW_WIDTH + GAP;
        previewX = left;
        previewY = top;

        this.addRenderableWidget(new PlainTextButton(
                left,
                top - 11,
                this.font.width("Capes"),
                10,
                Component.literal("Capes"),
                button -> this.minecraft.gui.setScreen(new CapeScreen(this, skin)),
                this.font));

        EditBox box = new EditBox(this.font, controls, top + 12, CONTROLS_WIDTH, 20, nameBox, USERNAME) {
            @Override
            public void insertText(String input) {
                // Only what a name can hold: letters, digits and underscores.
                super.insertText(PlayerName.filter(input));
            }
        };
        box.setMaxLength(PlayerName.MAX_LENGTH);
        box.setValue(name);
        box.setEditable(nameEditable);
        box.setResponder(value -> {
            name = value;
            updateDone();
        });
        nameBox = this.addRenderableWidget(box);

        SkinDropdown dropdown = new SkinDropdown(
                this.font, controls, top + 52, CONTROLS_WIDTH, 20, skin.choices(), skin.choice(), this::wear);
        skins = this.addRenderableWidget(dropdown);
        int half = (CONTROLS_WIDTH - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Add Skin"), button -> addSkin())
                .bounds(controls, top + 76, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Clear List"), button -> clearList())
                .bounds(controls + CONTROLS_WIDTH - half, top + 76, half, 20)
                .build());

        done = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(this.width / 2 - 100, top + PREVIEW_HEIGHT + 28, 200, 20)
                .build());
        updateDone();

        preview = null;
        addPreviewIfReady();
        if (nameEditable) {
            // Typing goes straight to the name, as on Eagler's screen.
            this.setInitialFocus(box);
        }
    }

    /** The turnable model, once the entity models are loaded (just after the title screen shows). */
    private void addPreviewIfReady() {
        if (preview != null || !DeferredModels.ready()) {
            return;
        }
        PlayerSkinWidget widget = new PlayerSkinWidget(
                PREVIEW_WIDTH - 4, PREVIEW_HEIGHT - 8, this.minecraft.getEntityModels(), skin::playerSkin);
        widget.setPosition(previewX + 2, previewY + 4);
        preview = this.addRenderableWidget(widget);
    }

    @Override
    public void tick() {
        addPreviewIfReady();
    }

    private void updateDone() {
        Button button = done;
        if (button != null) {
            button.active = nameAccepted();
        }
    }

    /** Whether Done can keep the name typed: it's one every server takes, or not the player's to change. */
    private boolean nameAccepted() {
        return !nameEditable || PlayerName.isValid(name);
    }

    private void wear(OwnSkin.Choice choice) {
        try {
            skin.choose(choice);
            message = CommonComponents.EMPTY;
        } catch (IOException e) {
            message = Component.literal("Can't use " + choice.label() + ": " + e.getMessage())
                    .withStyle(ChatFormatting.RED);
            refreshList();
        }
    }

    /** Opens the skin folder (the browser's file picker); the file comes back through a drop. */
    private void addSkin() {
        Path folder = this.minecraft.gameDirectory.toPath().resolve("incari").resolve("skin");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            message = Component.literal("Couldn't create " + folder).withStyle(ChatFormatting.RED);
            return;
        }
        message = Component.literal("Drop a 64x64 skin PNG on the window.");
        Blaze3D.openFolderToAddFiles(folder);
    }

    private void clearList() {
        skin.clearLibrary();
        refreshList();
        message = Component.literal("Removed the skins you added.");
    }

    private void refreshList() {
        SkinDropdown dropdown = skins;
        if (dropdown != null) {
            dropdown.setChoices(skin.choices(), skin.choice());
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        int added = 0;
        for (Path file : files) {
            try {
                skin.addSkinFile(file);
                added++;
            } catch (IOException e) {
                message = Component.literal("Can't use " + file.getFileName() + ": " + e.getMessage())
                        .withStyle(ChatFormatting.RED);
            }
        }
        refreshList();
        if (added > 0 && files.size() == added) {
            message = Component.literal(added == 1 ? "Skin added." : added + " skins added.");
        }
    }

    // The open list lies over the widgets under it: it takes clicks and scrolls first.

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SkinDropdown dropdown = skins;
        if (dropdown != null && dropdown.isOpen()) {
            return dropdown.listClicked(event.x(), event.y(), this.height);
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        SkinDropdown dropdown = skins;
        if (dropdown != null && dropdown.isOpen()) {
            return dropdown.listScrolled(scrollY);
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        SkinDropdown dropdown = skins;
        if (event.isEscape() && dropdown != null && dropdown.isOpen()) {
            dropdown.close();
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        if (event.isConfirmation() && nameAccepted()) {
            // Enter in the name box is Done.
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        SkinDropdown dropdown = skins;
        boolean listOpen = dropdown != null && dropdown.isOpen();
        // Nothing under the open list is hovered.
        super.extractRenderState(graphics, listOpen ? -1 : mouseX, listOpen ? -1 : mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        int controls = previewX + PREVIEW_WIDTH + GAP;
        graphics.text(this.font, USERNAME, controls, previewY + 2, LABEL);
        graphics.text(this.font, PLAYER_SKIN, controls, previewY + 42, LABEL);
        if (!nameEditable) {
            // Right of the label on its line, so it's cut short rather than drawn over it.
            String why = Ui.fit(
                    this.font,
                    PlayerName.editable() ? "Leave the world first" : "Set by your account",
                    CONTROLS_WIDTH - this.font.width(USERNAME) - 6);
            graphics.text(this.font, why, controls + CONTROLS_WIDTH - this.font.width(why), previewY + 2, 0xFF707070);
        }
        Component shown = nameAccepted() ? message : NAME_RULES;
        int line = previewY + PREVIEW_HEIGHT + 12;
        if (shown.equals(CommonComponents.EMPTY) && nameEditable && PlayerName.isSuggestion(name)) {
            // An auto-generated name repeats: warn before a server turns the player away for it.
            String note =
                    Ui.fit(this.font, "This auto-generated name may already be taken on servers", this.width - 16);
            graphics.centeredText(this.font, note, this.width / 2, line, 0xFFFFE080);
        } else {
            graphics.centeredText(this.font, shown, this.width / 2, line, 0xFFFFFFFF);
        }
        if (dropdown != null) {
            dropdown.extractList(graphics, mouseX, mouseY, this.height);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        // The model's box: Eagler's dark blue, with a border.
        graphics.fill(previewX, previewY, previewX + PREVIEW_WIDTH, previewY + PREVIEW_HEIGHT, 0xFF000018);
        graphics.outline(previewX, previewY, PREVIEW_WIDTH, PREVIEW_HEIGHT, 0xFFA0A0A0);
        if (preview == null) {
            graphics.centeredText(
                    this.font, "...", previewX + PREVIEW_WIDTH / 2, previewY + PREVIEW_HEIGHT / 2 - 4, LABEL);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Escape is Done, as elsewhere; but not with a name no server takes.
        return nameAccepted();
    }

    @Override
    public void onClose() {
        // Kept even unchanged: the name a first start suggested becomes the player's.
        if (nameEditable && nameAccepted()) {
            PlayerName.set(name);
        }
        next.run();
    }
}
