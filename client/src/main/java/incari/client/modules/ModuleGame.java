package incari.client.modules;

import incari.client.platform.ClientPlatform;
import incari.client.singleplayer.WorkerWorlds;
import incari.client.ui.SmoothFont;
import incari.client.ui.Theme;
import incari.framework.game.EffectCategory;
import incari.framework.game.EffectView;
import incari.framework.game.EquipmentSlot;
import incari.framework.game.Game;
import incari.framework.game.GameKey;
import incari.framework.game.GameOption;
import incari.framework.game.Hitboxes;
import incari.framework.game.ItemView;
import incari.framework.game.Perspective;
import incari.framework.game.PlayerView;
import incari.framework.game.ThemeColors;
import incari.framework.game.UiFont;
import incari.framework.game.Weather;
import incari.framework.waypoint.Waypoint;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The game as modules see it ({@link Game}, ADR 0050): the local player's place, equipment and
 * effects through reused views, the world they're in, the options a module may hold, a made-up
 * player for the HUD editor, and the waypoint screens (ADR 0051).
 * Game and render thread; the HUD reads the player a few dozen times a second, so nothing here
 * allocates once an effect's name is known.
 */
final class ModuleGame implements Game {

    /** Longest-lasting first, as the game's HUD orders them. */
    private static final Comparator<MobEffectInstance> ORDER = Collections.reverseOrder();

    /** Made once: {@code values()} copies its array each call. */
    private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

    /** One toast for modules' messages: a new one replaces the last instead of queueing behind it. */
    private static final SystemToast.SystemToastId MESSAGE = new SystemToast.SystemToastId();

    private final Player real = new Player();
    private @Nullable Player sample;

    /** Effect names in the language they were made in. */
    private final Map<Holder<MobEffect>, String> names = new HashMap<>();

    private String namesLanguage = "";

    /** Biome names in the language they were made in. */
    private final Map<Holder<Biome>, String> biomes = new HashMap<>();

    private String biomesLanguage = "";

    /** One view per item id, for {@link #item(String)}. */
    private final Map<String, Item> icons = new HashMap<>();

    /** Each key's short name, by what it's bound to ({@code KeyMapping.saveString}). */
    private final Map<String, String> keyNames = new HashMap<>();

    /** The keyboard layout and language {@link #keyNames} were made in. */
    private int keyNamesLayout;

    private String keyNamesLanguage = "";

    /** Goes up whenever the keyboard layout's key names change ({@link #keymapChanged}). */
    private static int keymapRevision;

    /** Goes up every client tick ({@link #newTick}): a view refreshed since, at this frame's partial tick, is current. */
    private static int tick;

    @Override
    public @Nullable PlayerView player() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        real.refreshIfStale(player);
        return real;
    }

    /**
     * From {@code IncariModules}, at the start of each client tick: the player's view is refreshed the
     * next time it's asked for. Within a frame it's refreshed once, however many modules ask.
     */
    static void newTick() {
        tick++;
    }

    @Override
    public PlayerView samplePlayer() {
        Player current = sample;
        if (current == null) {
            current = new Player();
            current.sample();
            sample = current;
        }
        return current;
    }

    @Override
    public @Nullable String world() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        IntegratedServer integrated = minecraft.getSingleplayerServer();
        if (integrated != null) {
            Path folder = integrated
                    .getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath()
                    .normalize()
                    .getFileName();
            return localWorld(folder == null ? "" : folder.toString());
        }
        String worker = WorkerWorlds.worldFolder();
        if (worker != null) {
            return localWorld(worker);
        }
        ServerData server = minecraft.getCurrentServer();
        return server == null ? null : "server/" + server.ip.toLowerCase(Locale.ROOT);
    }

    /** {@link #world()}'s name for the singleplayer world in {@code folder} (the save's folder name). */
    static String localWorld(String folder) {
        return "world/" + folder;
    }

    @Override
    public void openWaypoints() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new WaypointListScreen(minecraft.gui.screen(), IncariModules.system()));
    }

    @Override
    public void editWaypoint(Waypoint waypoint, boolean adding) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(
                new WaypointEditScreen(minecraft.gui.screen(), IncariModules.system(), waypoint, adding));
    }

    @Override
    public void openWorldMap() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            minecraft.gui.setScreen(new WorldMapScreen(minecraft.gui.screen(), IncariModules.system()));
        }
    }

    @Override
    public void rememberMap(boolean remember) {
        MapMemory.setRemember(remember);
    }

    @Override
    public double option(GameOption option) {
        if (option == GameOption.ATTACK_INDICATOR) {
            return switch (Minecraft.getInstance().options.attackIndicator().get()) {
                case OFF -> 0;
                case CROSSHAIR -> 1;
                case HOTBAR -> 2;
            };
        }
        return instance(option).get();
    }

    @Override
    public void setOption(GameOption option, double value) {
        if (option == GameOption.ATTACK_INDICATOR) {
            long index = Math.round(value);
            Minecraft.getInstance()
                    .options
                    .attackIndicator()
                    .set(
                            index == 0
                                    ? AttackIndicatorStatus.OFF
                                    : index == 2 ? AttackIndicatorStatus.HOTBAR : AttackIndicatorStatus.CROSSHAIR);
        } else {
            instance(option).set(value);
        }
        Minecraft.getInstance().options.save();
    }

    @Override
    public boolean packSelected(String packId) {
        // What the Resource Packs screen last saved: a field read and a short list's lookup a tick.
        return Minecraft.getInstance().options.resourcePacks.contains(packId);
    }

    @Override
    public boolean selectPack(String packId, boolean on) {
        // As the Resource Packs screen does it: the pack put at its default place, under packs
        // fixed on top (Incari's), which the repository's addPack would put it over; then the
        // options saved and the resources reloaded.
        Minecraft minecraft = Minecraft.getInstance();
        PackRepository packs = minecraft.getResourcePackRepository();
        Pack pack = packs.getPack(packId);
        if (pack == null) {
            return !on;
        }
        List<Pack> selected = new ArrayList<>(packs.getSelectedPacks());
        boolean changed = selected.contains(pack) != on;
        if (changed) {
            if (on) {
                pack.getDefaultPosition().insert(selected, pack, Pack::selectionConfig, false);
            } else {
                selected.remove(pack);
            }
            List<String> ids = new ArrayList<>(selected.size());
            for (Pack each : selected) {
                ids.add(each.getId());
            }
            packs.setSelected(ids);
            minecraft.options.updateResourcePacks(packs);
        }
        return packSelected(packId) == on;
    }

    @Override
    public float attackStrength() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 1;
        }
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        return player.getAttackStrengthScale(partial);
    }

    @Override
    public boolean onTarget() {
        Entity target = Minecraft.getInstance().crosshairPickEntity;
        return target instanceof LivingEntity && target.isAlive();
    }

    @Override
    public Perspective perspective() {
        return switch (Minecraft.getInstance().options.getCameraType()) {
            case FIRST_PERSON -> Perspective.FIRST_PERSON;
            case THIRD_PERSON_BACK -> Perspective.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> Perspective.THIRD_PERSON_FRONT;
        };
    }

    @Override
    public void setPerspective(Perspective perspective) {
        Minecraft.getInstance()
                .options
                .setCameraType(
                        switch (perspective) {
                            case FIRST_PERSON -> CameraType.FIRST_PERSON;
                            case THIRD_PERSON_BACK -> CameraType.THIRD_PERSON_BACK;
                            case THIRD_PERSON_FRONT -> CameraType.THIRD_PERSON_FRONT;
                        });
    }

    @Override
    public void overrideSky(@Nullable Weather weather, int timeOfDay) {
        ModuleSky.override(weather, timeOfDay);
    }

    @Override
    public void overrideBrightness(float level) {
        ModuleEffects.brightness(level);
    }

    @Override
    public void overrideBlockOutline(int argb, float width, int fillArgb) {
        ModuleEffects.outline(argb, width, fillArgb);
    }

    @Override
    public void overrideHitboxes(@Nullable Hitboxes hitboxes) {
        ModuleHitboxes.hold(hitboxes);
    }

    @Override
    public void overrideHitColor(int rgb, int strength) {
        ModuleEffects.hitColor(rgb, strength);
    }

    @Override
    public void overrideGlintColor(int rgb) {
        ModuleEffects.glintColor(rgb);
    }

    @Override
    public void overrideFov(float scale) {
        ModuleCamera.fovScale(scale);
    }

    @Override
    public void overrideSmoothCamera(boolean on) {
        ModuleCamera.smooth(on);
    }

    @Override
    public void overrideSaturation(int rgb) {
        ModuleHunger.saturation(rgb);
    }

    @Override
    public void effectOnTarget(incari.framework.game.Effect effect, int count, boolean sound) {
        ModuleEffects.effect(effect, count, sound);
    }

    @Override
    public void applyFont(UiFont font) {
        // Each font's size against Red Hat Display's, so lowercase letters stand about as tall and
        // a line about as wide: the game's layouts were made for its width.
        switch (font) {
            case RED_HAT_DISPLAY -> SmoothFont.useFont("red_hat_display.ttf", 1.0F);
            case MINECRAFT -> SmoothFont.useFont(null, 1.0F);
            case INTER -> SmoothFont.useFont("inter.ttf", 0.92F);
            case NUNITO -> SmoothFont.useFont("nunito.ttf", 1.0F);
            case POPPINS -> SmoothFont.useFont("poppins.ttf", 0.91F);
            case JETBRAINS_MONO -> SmoothFont.useFont("jetbrains_mono.ttf", 0.85F);
            case MICHROMA -> SmoothFont.useFont("michroma.ttf", 0.78F);
        }
    }

    @Override
    public void applyTheme(@Nullable ThemeColors theme) {
        if (theme == null) {
            Theme.restore();
        } else {
            Theme.apply(
                    theme.accent(),
                    theme.text(),
                    theme.muted(),
                    theme.panel(),
                    theme.panelOpacity(),
                    theme.hudOpacity(),
                    theme.radius());
        }
    }

    @Override
    public String clipboard() {
        String text = ClientPlatform.get().shell().clipboardText();
        return text == null ? "" : text;
    }

    @Override
    public void copy(String text) {
        ClientPlatform.get().shell().setClipboardText(text);
    }

    @Override
    public void showMessage(String title, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        // Queued: a module's failure may be reported mid-frame, from the HUD's drawing.
        minecraft.execute(() -> SystemToast.addOrUpdate(
                minecraft.gui.toastManager(), MESSAGE, Component.literal(title), Component.literal(message)));
    }

    @Override
    public void askText(String title, String prompt, String initial, Consumer<String> entered) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new TextEntryScreen(minecraft.gui.screen(), title, prompt, initial, entered));
    }

    @Override
    public void openScreenshots() {
        ModuleScreenshots.openGallery(Minecraft.getInstance().gui.screen());
    }

    @Override
    public boolean copyScreenshot(String fileName) {
        return ModuleScreenshots.copy(ModuleScreenshots.folder().resolve(fileName));
    }

    private static OptionInstance<Double> instance(GameOption option) {
        var options = Minecraft.getInstance().options;
        return switch (option) {
            case TEXT_BACKGROUND_OPACITY -> options.textBackgroundOpacity();
            case DAMAGE_TILT -> options.damageTiltStrength();
            case ATTACK_INDICATOR -> throw new IllegalArgumentException("Attack Indicator is a choice");
        };
    }

    @Override
    public boolean isDown(GameKey key) {
        return mapping(key).isDown();
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // The language manager keeps one string per language.
    public String keyName(GameKey key) {
        KeyMapping mapping = mapping(key);
        // Made once per binding, layout and language: the HUD asks a few dozen times a second.
        // The page learns the layout's names after start (the Keyboard Map API answers late).
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (keyNamesLayout != keymapRevision || language != keyNamesLanguage) {
            keyNames.clear();
            keyNamesLayout = keymapRevision;
            keyNamesLanguage = language;
        }
        String bound = mapping.saveString();
        String name = keyNames.get(bound);
        if (name == null) {
            name = shortName(mapping.getTranslatedKeyMessage().getString());
            keyNames.put(bound, name);
        }
        return name;
    }

    /** The keyboard layout's key names changed: the names made so far are made again. Game thread. */
    static void keymapChanged() {
        keymapRevision++;
    }

    /** "LMB" for "Left Button": the mouse's names are too long for a key's box. */
    private static String shortName(String name) {
        return switch (name) {
            case "Left Button" -> "LMB";
            case "Right Button" -> "RMB";
            case "Middle Button" -> "MMB";
            case "Left Shift" -> "Shift";
            case "Left Control" -> "Ctrl";
            case "Left Alt" -> "Alt";
            default -> name;
        };
    }

    private static KeyMapping mapping(GameKey key) {
        Options options = Minecraft.getInstance().options;
        return switch (key) {
            case FORWARD -> options.keyUp;
            case LEFT -> options.keyLeft;
            case BACK -> options.keyDown;
            case RIGHT -> options.keyRight;
            case JUMP -> options.keyJump;
            case SNEAK -> options.keyShift;
            case SPRINT -> options.keySprint;
            case ATTACK -> options.keyAttack;
            case USE -> options.keyUse;
        };
    }

    @Override
    public int fps() {
        return Minecraft.getInstance().getFps();
    }

    @Override
    public ItemView item(String itemId) {
        Item icon = icons.get(itemId);
        if (icon == null) {
            icon = new Item();
            Identifier id = Identifier.tryParse(itemId);
            if (id != null) {
                icon.stack = BuiltInRegistries.ITEM
                        .getOptional(id)
                        .map(ItemStack::new)
                        .orElse(ItemStack.EMPTY);
            }
            icons.put(itemId, icon);
        }
        return icon;
    }

    /** {@code biome}'s name in the current language, made once per biome and language. */
    @SuppressWarnings("ReferenceEquality") // The language manager keeps one string per language.
    private String biomeName(Holder<Biome> biome) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (language != biomesLanguage) {
            biomes.clear();
            biomesLanguage = language;
        }
        String name = biomes.get(biome);
        if (name == null) {
            name = biome.unwrapKey()
                    .map(key -> Component.translatable(Util.makeDescriptionId("biome", key.identifier()))
                            .getString())
                    .orElse("");
            biomes.put(biome, name);
        }
        return name;
    }

    /** {@code effect}'s name in the current language, made once per effect and language. */
    @SuppressWarnings("ReferenceEquality") // The language manager keeps one string per language.
    private String name(Holder<MobEffect> effect) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (language != namesLanguage) {
            names.clear();
            namesLanguage = language;
        }
        String name = names.get(effect);
        if (name == null) {
            name = effect.value().getDisplayName().getString();
            names.put(effect, name);
        }
        return name;
    }

    /** A player's view, refilled from the game each time it's handed out. */
    private final class Player implements PlayerView {

        private final Map<EquipmentSlot, Item> items = new EnumMap<>(EquipmentSlot.class);
        private final List<MobEffectInstance> shown = new ArrayList<>();
        private final List<Effect> effects = new ArrayList<>();
        private double x;
        private double y;
        private double z;
        private float heading;
        private @Nullable ResourceKey<Level> dimensionKey;
        private String dimension = PlayerView.OVERWORLD;
        private boolean dead;
        private @Nullable LocalPlayer source;

        /** The tick and partial tick of the last refresh. */
        private int refreshedTick = -1;

        private float refreshedPartial = Float.NaN;

        /** Goes up with each refresh, for what's kept per refresh. */
        private int refreshes;

        /** The biome's name, and where it was looked up: the player's block and level. */
        private String biomeName = "";

        private long biomeAt = Long.MIN_VALUE;
        private @Nullable Level biomeLevel;

        /** The last {@link #count}: of which item, its answer, and the refresh it's of. */
        private @Nullable String countOf;

        private int countValue;
        private int countRefresh = -1;

        Player() {
            for (EquipmentSlot slot : SLOTS) {
                items.put(slot, new Item());
            }
        }

        /** Refreshes the view unless it already is of this player, tick and frame. */
        @SuppressWarnings("ReferenceEquality") // The same player object, not an equal one.
        void refreshIfStale(LocalPlayer player) {
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            if (player == source && refreshedTick == tick && partial == refreshedPartial) {
                return;
            }
            refreshedTick = tick;
            refreshedPartial = partial;
            refreshes++;
            refresh(player, partial);
        }

        @SuppressWarnings("ReferenceEquality") // A dimension's key is one object: its id string is made once.
        private void refresh(LocalPlayer player, float partial) {
            // Where the player is drawn this frame, between ticks: what follows them moves smoothly.
            x = Mth.lerp(partial, player.xo, player.getX());
            y = Mth.lerp(partial, player.yo, player.getY());
            z = Mth.lerp(partial, player.zo, player.getZ());
            // The game's yaw is 0 facing south (+z), growing toward the west; a bearing is from north, toward the east.
            heading = Mth.positiveModulo(player.getViewYRot(partial) + 180.0F, 360.0F);
            ResourceKey<Level> key = player.level().dimension();
            if (key != dimensionKey) {
                dimensionKey = key;
                dimension = key.identifier().toString();
            }
            dead = player.isDeadOrDying();
            source = player;
            for (EquipmentSlot slot : SLOTS) {
                slot(slot).stack = player.getItemBySlot(gameSlot(slot));
            }
            fillEffects(player.getActiveEffects());
        }

        /** Diamond armor at a few levels of wear, a sword, and three effects. */
        void sample() {
            slot(EquipmentSlot.HEAD).stack = worn(Items.DIAMOND_HELMET, 0.1F);
            slot(EquipmentSlot.CHEST).stack = worn(Items.DIAMOND_CHESTPLATE, 0.35F);
            slot(EquipmentSlot.LEGS).stack = worn(Items.DIAMOND_LEGGINGS, 0.6F);
            slot(EquipmentSlot.FEET).stack = worn(Items.DIAMOND_BOOTS, 0.85F);
            slot(EquipmentSlot.MAINHAND).stack = worn(Items.DIAMOND_SWORD, 0.2F);
            List<MobEffectInstance> sampleEffects = List.of(
                    new MobEffectInstance(MobEffects.SPEED, 1660, 1),
                    new MobEffectInstance(MobEffects.STRENGTH, 3600, 0),
                    new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 160, 0));
            fillEffects(sampleEffects);
        }

        private static ItemStack worn(net.minecraft.world.item.Item item, float wear) {
            ItemStack stack = new ItemStack(item);
            stack.setDamageValue(Math.round(stack.getMaxDamage() * wear));
            return stack;
        }

        private void fillEffects(Collection<MobEffectInstance> active) {
            shown.clear();
            for (MobEffectInstance instance : active) {
                if (instance.showIcon()) {
                    shown.add(instance);
                }
            }
            // Under 32 elements the sort is an insertion sort in place: nothing allocated.
            shown.sort(ORDER);
            while (effects.size() < shown.size()) {
                effects.add(new Effect());
            }
            for (int i = 0; i < shown.size(); i++) {
                effects.get(i).instance = shown.get(i);
            }
        }

        private Item slot(EquipmentSlot slot) {
            Item item = items.get(slot);
            if (item == null) {
                throw new IllegalStateException("No view for " + slot);
            }
            return item;
        }

        @Override
        public ItemView item(EquipmentSlot slot) {
            return slot(slot);
        }

        @Override
        public int effectCount() {
            return shown.size();
        }

        @Override
        public EffectView effect(int index) {
            if (index >= shown.size()) {
                throw new IndexOutOfBoundsException(index);
            }
            return effects.get(index);
        }

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return y;
        }

        @Override
        public double z() {
            return z;
        }

        @Override
        public float heading() {
            return heading;
        }

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public boolean isDead() {
            return dead;
        }

        @Override
        @SuppressWarnings("ReferenceEquality") // Another level object is another world or dimension.
        public String biome() {
            LocalPlayer player = source;
            if (player == null) {
                return "";
            }
            // Looked up again only on another block: the biome lookup blends the noise's cells.
            long at = player.blockPosition().asLong();
            Level level = player.level();
            if (at != biomeAt || level != biomeLevel) {
                biomeAt = at;
                biomeLevel = level;
                biomeName = biomeName(level.getBiome(player.blockPosition()));
            }
            return biomeName;
        }

        @Override
        @SuppressWarnings("ReferenceEquality") // A module asks with the same constant each time.
        public int count(String itemId) {
            LocalPlayer player = source;
            if (player == null) {
                return 0;
            }
            // Counted once a refresh: an element asks while sizing and again while drawing.
            if (itemId == countOf && countRefresh == refreshes) {
                return countValue;
            }
            countOf = itemId;
            countRefresh = refreshes;
            countValue = countNow(player, itemId);
            return countValue;
        }

        private int countNow(LocalPlayer player, String itemId) {
            Item kind = (Item) ModuleGame.this.item(itemId);
            if (kind.stack.isEmpty()) {
                return 0;
            }
            Inventory inventory = player.getInventory();
            int count = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.is(kind.stack.getItem())) {
                    count += stack.getCount();
                }
            }
            return count;
        }
    }

    private static net.minecraft.world.entity.EquipmentSlot gameSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> net.minecraft.world.entity.EquipmentSlot.HEAD;
            case CHEST -> net.minecraft.world.entity.EquipmentSlot.CHEST;
            case LEGS -> net.minecraft.world.entity.EquipmentSlot.LEGS;
            case FEET -> net.minecraft.world.entity.EquipmentSlot.FEET;
            case MAINHAND -> net.minecraft.world.entity.EquipmentSlot.MAINHAND;
            case OFFHAND -> net.minecraft.world.entity.EquipmentSlot.OFFHAND;
        };
    }

    /** One slot's stack; {@link ModuleHud} draws {@link #stack}. */
    static final class Item implements ItemView {

        ItemStack stack = ItemStack.EMPTY;

        /** The item {@link #id} was made for, so it's made again only when the item changes. */
        private net.minecraft.world.item.@Nullable Item idOf;

        private String id = "";

        @Override
        @SuppressWarnings("ReferenceEquality") // Items are one object each.
        public String id() {
            if (stack.isEmpty()) {
                return "";
            }
            net.minecraft.world.item.Item item = stack.getItem();
            if (item != idOf) {
                idOf = item;
                id = BuiltInRegistries.ITEM.getKey(item).toString();
            }
            return id;
        }

        @Override
        public float cooldown() {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || stack.isEmpty()) {
                return 0;
            }
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            return player.getCooldowns().getCooldownPercent(stack, partial);
        }

        @Override
        public boolean isEmpty() {
            return stack.isEmpty();
        }

        @Override
        public int count() {
            return stack.getCount();
        }

        @Override
        public boolean isDamageable() {
            return stack.isDamageableItem();
        }

        @Override
        public int damage() {
            return stack.getDamageValue();
        }

        @Override
        public int maxDamage() {
            return stack.getMaxDamage();
        }
    }

    /** One effect; {@link ModuleHud} draws {@link #effect()}'s sprite. */
    final class Effect implements EffectView {

        @Nullable
        MobEffectInstance instance;

        MobEffectInstance instance() {
            MobEffectInstance current = instance;
            if (current == null) {
                throw new IllegalStateException("No effect");
            }
            return current;
        }

        Holder<MobEffect> effect() {
            return instance().getEffect();
        }

        @Override
        public String name() {
            return ModuleGame.this.name(effect());
        }

        @Override
        public int amplifier() {
            return instance().getAmplifier();
        }

        @Override
        public int duration() {
            return instance().getDuration();
        }

        @Override
        public boolean isInfinite() {
            return instance().isInfiniteDuration();
        }

        @Override
        public EffectCategory category() {
            MobEffectCategory category = effect().value().getCategory();
            return switch (category) {
                case BENEFICIAL -> EffectCategory.BENEFICIAL;
                case HARMFUL -> EffectCategory.HARMFUL;
                case NEUTRAL -> EffectCategory.NEUTRAL;
            };
        }
    }
}
