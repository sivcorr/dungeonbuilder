package dev.dungeonbuilder.gui;

import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.loot.LootModels.ChestLootConfig;
import dev.dungeonbuilder.loot.LootModels.LootMode;
import dev.dungeonbuilder.loot.LootModels.LootProfile;
import dev.dungeonbuilder.loot.LootProfileStorage;
import dev.dungeonbuilder.network.ModNetworking.OpenServerChestConfigPayload;
import dev.dungeonbuilder.network.ModNetworking.SaveServerChestConfigPayload;
import dev.dungeonbuilder.network.ModNetworking.ServerContainerInfo;
import dev.dungeonbuilder.storage.LocalLibrary;
import dev.dungeonbuilder.structure.DungeonStructure;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Configura el modo de cada cofre/contenedor (Original vs Loot aleatorio + Loot por jugador). */
public class ChestLootConfigScreen extends Screen {

    private static final int ENTRY_HEIGHT = 44;

    private static final int COLOR_BACKGROUND = 0xF00B1611;
    private static final int COLOR_HEADER = 0xFF132A1F;
    private static final int COLOR_PANEL = 0xF0101F18;
    private static final int COLOR_PANEL_INNER = 0xFF0C1812;

    private static final int COLOR_ACCENT = 0xFF4ADE80;
    private static final int COLOR_ACCENT_LIGHT = 0xFFA7F3D0;
    private static final int COLOR_ACCENT_DARK = 0xFF256D49;
    private static final int COLOR_SERVER_ACCENT = 0xFF38BDF8;
    private static final int COLOR_GOLD = 0xFFFACC15;

    private static final int COLOR_TEXT = 0xFFEEFBF3;
    private static final int COLOR_MUTED = 0xFF93B3A1;
    private static final int COLOR_BORDER = 0xFF2B5942;
    private static final int COLOR_ERROR = 0xFFFF8585;

    private static final class ContainerEntry {
        final int x, y, z;
        final String blockName;
        final List<ItemStack> originalItems;
        final List<String> rawOriginalSnbt;
        LootMode mode;
        String profileId;
        boolean perPlayer;

        ContainerEntry(int x, int y, int z, String blockName, List<ItemStack> originalItems,
                       List<String> rawOriginalSnbt, LootMode mode, String profileId, boolean perPlayer) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockName = blockName;
            this.originalItems = originalItems;
            this.rawOriginalSnbt = rawOriginalSnbt;
            this.mode = mode;
            this.profileId = profileId;
            this.perPlayer = perPlayer;
        }
    }

    private final Screen parent;
    private final boolean serverMode;
    private final String structureId;
    private final String structureName;
    private final List<LootProfile> serverInitialProfiles = new ArrayList<>();

    private byte[] rawBytes;
    private final List<ContainerEntry> containers = new ArrayList<>();
    private List<LootProfile> availableProfiles = new ArrayList<>();
    private ContainerEntry selected;

    private boolean saving = false;
    private double containerScroll;
    private double profileScroll;

    private String statusMessage;
    private int statusColor = COLOR_MUTED;

    private int panelLeft, panelTop, panelWidth, panelHeight;
    private int listX, listY, listWidth, listHeight;
    private int detailX, detailWidth, contentBottom;
    private int profileListY, profileListHeight;

    private UiButton originalModeBtn;
    private UiButton randomModeBtn;
    private UiButton perPlayerBtn;
    private UiButton applyAllBtn;
    private UiButton editProfilesBtn;
    private UiButton backBtn;
    private UiButton saveBtn;

    public ChestLootConfigScreen(Screen parent, String structureId, String structureName) {
        super(Component.literal("Cofres y Loot: " + (structureName == null ? structureId : structureName)));
        this.parent = parent;
        this.serverMode = false;
        this.structureId = structureId;
        this.structureName = structureName == null ? structureId : structureName;
        this.statusMessage = "Elige cada cofre para mantener su inventario, loot aleatorio y si es por jugador.";
    }

    public ChestLootConfigScreen(Screen parent, OpenServerChestConfigPayload payload) {
        super(Component.literal("Cofres del Servidor: " + payload.structureName()));
        this.parent = parent;
        this.serverMode = true;
        this.structureId = payload.structureId();
        this.structureName = payload.structureName();
        this.statusMessage = "Editando cofres directamente en el servidor.";

        for (LootProfile lp : payload.profiles()) {
            this.serverInitialProfiles.add(lp.copy());
        }
        for (ServerContainerInfo c : payload.containers()) {
            this.containers.add(new ContainerEntry(
                    c.x(), c.y(), c.z(),
                    c.blockName(),
                    new ArrayList<>(),
                    c.originalItemsSnbt(),
                    LootMode.fromString(c.modeName()),
                    c.profileId(),
                    c.perPlayer()
            ));
        }
    }

    @Override
    protected void init() {
        panelWidth = Math.max(1, Math.min(860, width - 12));
        panelHeight = Math.max(1, Math.min(430, height - 12));

        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int contentWidth = Math.max(1, panelWidth - 32);
        listX = panelLeft + 16;
        listY = panelTop + 56;
        listWidth = Math.max(1, (int) (contentWidth * 0.42));
        detailX = listX + listWidth + 12;
        detailWidth = Math.max(1, panelLeft + panelWidth - 16 - detailX);
        contentBottom = panelTop + panelHeight - 40;
        listHeight = Math.max(1, contentBottom - listY);

        profileListY = listY + 150;
        profileListHeight = Math.max(36, contentBottom - profileListY - 38);

        int halfBtn = Math.max(1, (detailWidth - 28) / 2);
        originalModeBtn = new UiButton(
                detailX + 10,
                listY + 86,
                halfBtn,
                22,
                Component.literal("Inventario original"),
                "O",
                ButtonStyle.PRIMARY
        );
        randomModeBtn = new UiButton(
                detailX + 18 + halfBtn,
                listY + 86,
                halfBtn,
                22,
                Component.literal("Loot aleatorio"),
                "L",
                ButtonStyle.SECONDARY
        );

        perPlayerBtn = new UiButton(
                detailX + 10,
                listY + 113,
                Math.max(1, detailWidth - 20),
                21,
                Component.literal("Loot por jugador (Multiplayer): NO"),
                "M",
                ButtonStyle.SECONDARY
        );

        applyAllBtn = new UiButton(
                detailX + 10,
                contentBottom - 30,
                halfBtn,
                22,
                Component.literal("Aplicar a todos"),
                "*",
                ButtonStyle.SECONDARY
        );

        editProfilesBtn = new UiButton(
                detailX + 18 + halfBtn,
                contentBottom - 30,
                halfBtn,
                22,
                Component.literal("Editar perfiles"),
                "+",
                ButtonStyle.SECONDARY
        );

        backBtn = new UiButton(
                panelLeft + panelWidth - 212,
                panelTop + panelHeight - 31,
                84,
                22,
                Component.literal("Volver"),
                "<",
                ButtonStyle.SECONDARY
        );

        saveBtn = new UiButton(
                panelLeft + panelWidth - 120,
                panelTop + panelHeight - 31,
                104,
                22,
                Component.literal("Guardar"),
                "S",
                ButtonStyle.PRIMARY
        );

        loadData();
    }

    private void loadData() {
        Map<String, LootProfile> merged = new LinkedHashMap<>();
        for (LootProfile p : LootProfileStorage.listProfiles()) {
            merged.put(p.id.toLowerCase(Locale.ROOT), p.copy());
        }
        for (LootProfile sp : serverInitialProfiles) {
            merged.putIfAbsent(sp.id.toLowerCase(Locale.ROOT), sp.copy());
        }
        availableProfiles = new ArrayList<>(merged.values());
        String fallbackProfileId = availableProfiles.isEmpty() ? "comun" : availableProfiles.get(0).id;

        if (serverMode) {
            if (minecraft != null && minecraft.level != null) {
                HolderLookup.Provider registries = minecraft.level.registryAccess();
                for (ContainerEntry c : containers) {
                    if (c.originalItems.isEmpty() && c.rawOriginalSnbt != null) {
                        for (String snbt : c.rawOriginalSnbt) {
                            ItemStack st = LootProfileStorage.snbtToItemStack(snbt, registries);
                            if (!st.isEmpty()) {
                                c.originalItems.add(st);
                            }
                        }
                    }
                    if (c.profileId == null || c.profileId.isBlank() || findProfileName(c.profileId) == null) {
                        c.profileId = fallbackProfileId;
                    }
                }
            }
            if (selected == null && !containers.isEmpty()) {
                selected = containers.get(0);
            }
            if (containers.isEmpty()) {
                statusMessage = "No se encontraron cofres ni contenedores en esta estructura.";
                statusColor = COLOR_GOLD;
            }
            updateButtonsState();
            return;
        }

        if (!containers.isEmpty()) {
            for (ContainerEntry c : containers) {
                if (findProfileName(c.profileId) == null) {
                    c.profileId = fallbackProfileId;
                }
            }
            updateButtonsState();
            return;
        }

        if (minecraft == null || minecraft.level == null) {
            return;
        }
        try {
            rawBytes = LocalLibrary.readStructureBytes(structureId);
            HolderLookup.Provider registries = minecraft.level.registryAccess();
            DungeonStructure structure = DungeonStructure.fromCompressedBytes(
                    rawBytes, minecraft.level.holderLookup(Registries.BLOCK));

            for (LootProfile emb : structure.embeddedProfiles().values()) {
                boolean exists = availableProfiles.stream()
                        .anyMatch(p -> p.id.equalsIgnoreCase(emb.id));
                if (!exists) {
                    availableProfiles.add(emb.copy());
                }
            }

            containers.clear();
            for (DungeonStructure.BlockEntityRecord rec : structure.blockEntities()) {
                BlockState state = structure.getRawBlockState(rec.x(), rec.y(), rec.z());
                if (!DungeonStructure.isContainerBlockEntity(rec, state)) {
                    continue;
                }

                List<ItemStack> preview = new ArrayList<>();
                if (rec.data().contains("Items", Tag.TAG_LIST)) {
                    ListTag itemsTag = rec.data().getList("Items", Tag.TAG_COMPOUND);
                    for (int i = 0; i < itemsTag.size(); i++) {
                        ItemStack parsed = ItemStack.parseOptional(registries, itemsTag.getCompound(i));
                        if (!parsed.isEmpty()) {
                            preview.add(parsed);
                        }
                    }
                }

                ChestLootConfig cfg = structure.getChestLootConfig(rec.x(), rec.y(), rec.z());
                LootMode mode = cfg != null ? cfg.mode() : LootMode.ORIGINAL;
                String profileId = (cfg != null && cfg.profileId() != null && !cfg.profileId().isBlank())
                        ? cfg.profileId()
                        : fallbackProfileId;
                boolean perPlayer = cfg != null && cfg.perPlayer();

                containers.add(new ContainerEntry(
                        rec.x(), rec.y(), rec.z(),
                        state.getBlock().getName().getString(),
                        preview,
                        List.of(),
                        mode,
                        profileId,
                        perPlayer
                ));
            }

            selected = containers.isEmpty() ? null : containers.get(0);
            if (containers.isEmpty()) {
                statusMessage = "No se encontraron cofres ni contenedores en esta estructura.";
                statusColor = COLOR_GOLD;
            }
            updateButtonsState();
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error cargando cofres de {}", structureId, e);
            statusMessage = "No se pudo leer la estructura.";
            statusColor = COLOR_ERROR;
        }
    }

    private void updateButtonsState() {
        boolean hasSel = selected != null;
        if (originalModeBtn != null) {
            originalModeBtn.active = hasSel && !saving;
            originalModeBtn.style = (hasSel && selected.mode == LootMode.ORIGINAL)
                    ? ButtonStyle.PRIMARY : ButtonStyle.SECONDARY;
        }
        if (randomModeBtn != null) {
            randomModeBtn.active = hasSel && !saving;
            randomModeBtn.style = (hasSel && selected.mode == LootMode.RANDOM_LOOT)
                    ? ButtonStyle.PRIMARY : ButtonStyle.SECONDARY;
        }
        if (perPlayerBtn != null) {
            perPlayerBtn.active = hasSel && !saving;
            boolean pp = hasSel && selected.perPlayer;
            perPlayerBtn.label = Component.literal("Loot por jugador (Multiplayer): " + (pp ? "SÍ" : "NO"));
            perPlayerBtn.style = pp ? ButtonStyle.PRIMARY : ButtonStyle.SECONDARY;
        }
        if (applyAllBtn != null) {
            applyAllBtn.active = hasSel && containers.size() > 1 && !saving;
        }
        if (saveBtn != null) {
            saveBtn.active = !containers.isEmpty() && !saving;
        }
    }

    private String findProfileName(String id) {
        if (id == null) return null;
        for (LootProfile p : availableProfiles) {
            if (p.id.equalsIgnoreCase(id)) {
                return p.name;
            }
        }
        return null;
    }

    private LootProfile findProfile(String id) {
        if (id == null) return null;
        for (LootProfile p : availableProfiles) {
            if (p.id.equalsIgnoreCase(id)) {
                return p;
            }
        }
        return null;
    }

    private void saveChanges() {
        if (containers.isEmpty() || saving) {
            return;
        }

        List<ChestLootConfig> configs = new ArrayList<>();
        Map<String, LootProfile> usedProfiles = new LinkedHashMap<>();

        for (ContainerEntry c : containers) {
            configs.add(new ChestLootConfig(c.x, c.y, c.z, c.mode, c.profileId, c.perPlayer));
            if (c.mode == LootMode.RANDOM_LOOT) {
                LootProfile p = findProfile(c.profileId);
                if (p != null) {
                    usedProfiles.put(p.id.toLowerCase(Locale.ROOT), p);
                }
            }
        }

        if (serverMode) {
            saving = true;
            updateButtonsState();
            statusMessage = "Guardando cofres en el servidor...";
            statusColor = COLOR_ACCENT_LIGHT;
            ClientPlayNetworking.send(new SaveServerChestConfigPayload(
                    structureId,
                    configs,
                    new ArrayList<>(usedProfiles.values())
            ));
            return;
        }

        if (rawBytes == null) {
            return;
        }
        try {
            byte[] updated = DungeonStructure.updateLootInDungeonBytes(rawBytes, configs, usedProfiles);
            LocalLibrary.updateStructureBytes(structureId, updated);
            rawBytes = updated;

            statusMessage = "¡Cofres guardados! Si exportas la estructura, llevará este loot.";
            statusColor = COLOR_ACCENT_LIGHT;
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error guardando loot en {}", structureId, e);
            statusMessage = "Error guardando configuración de cofres.";
            statusColor = COLOR_ERROR;
        }
    }

    public void handleServerSaveResult(boolean success, String message) {
        saving = false;
        updateButtonsState();
        statusMessage = message;
        statusColor = success ? COLOR_ACCENT_LIGHT : COLOR_ERROR;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }

        if (backBtn.contains(mouseX, mouseY)) {
            playClickSound();
            onClose();
            return true;
        }

        if (saveBtn.contains(mouseX, mouseY)) {
            if (saveBtn.active) {
                playClickSound();
                saveChanges();
            }
            return true;
        }

        if (editProfilesBtn.contains(mouseX, mouseY)) {
            playClickSound();
            if (minecraft != null) {
                minecraft.setScreen(new LootProfilesScreen(this));
            }
            return true;
        }

        if (originalModeBtn.contains(mouseX, mouseY) && originalModeBtn.active) {
            playClickSound();
            selected.mode = LootMode.ORIGINAL;
            updateButtonsState();
            return true;
        }

        if (randomModeBtn.contains(mouseX, mouseY) && randomModeBtn.active) {
            playClickSound();
            selected.mode = LootMode.RANDOM_LOOT;
            if ((selected.profileId == null || selected.profileId.isBlank()) && !availableProfiles.isEmpty()) {
                selected.profileId = availableProfiles.get(0).id;
            }
            updateButtonsState();
            return true;
        }

        if (perPlayerBtn.contains(mouseX, mouseY) && perPlayerBtn.active) {
            playClickSound();
            selected.perPlayer = !selected.perPlayer;
            updateButtonsState();
            statusMessage = selected.perPlayer
                    ? "Cada jugador tendrá su propio loot independiente en este cofre."
                    : "El cofre será compartido entre todos los jugadores.";
            statusColor = COLOR_ACCENT_LIGHT;
            return true;
        }

        if (applyAllBtn.contains(mouseX, mouseY) && applyAllBtn.active) {
            playClickSound();
            for (ContainerEntry c : containers) {
                c.mode = selected.mode;
                c.profileId = selected.profileId;
                c.perPlayer = selected.perPlayer;
            }
            String modeDesc = selected.mode == LootMode.ORIGINAL
                    ? "Inventario original"
                    : "Loot: " + findProfileName(selected.profileId);
            String ppDesc = selected.perPlayer ? " (Por jugador)" : "";
            statusMessage = "Aplicado '" + modeDesc + ppDesc + "' a los " + containers.size() + " cofres.";
            statusColor = COLOR_ACCENT_LIGHT;
            return true;
        }

        if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= listY && mouseY <= contentBottom) {
            int idx = (int) ((mouseY - listY + containerScroll) / ENTRY_HEIGHT);
            if (idx >= 0 && idx < containers.size()) {
                playClickSound();
                selected = containers.get(idx);
                updateButtonsState();
                return true;
            }
        }

        if (selected != null && selected.mode == LootMode.RANDOM_LOOT
                && mouseX >= detailX + 10 && mouseX <= detailX + detailWidth - 10
                && mouseY >= profileListY && mouseY <= profileListY + profileListHeight) {
            int rowH = 26;
            int idx = (int) ((mouseY - profileListY + profileScroll) / rowH);
            if (idx >= 0 && idx < availableProfiles.size()) {
                playClickSound();
                selected.profileId = availableProfiles.get(idx).id;
                statusMessage = "Cofre asignado a perfil: " + availableProfiles.get(idx).name;
                statusColor = COLOR_ACCENT_LIGHT;
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= listY && mouseY <= contentBottom) {
            containerScroll -= scrollY * 18.0;
            double max = Math.max(0, containers.size() * ENTRY_HEIGHT - listHeight);
            containerScroll = Math.max(0, Math.min(containerScroll, max));
            return true;
        }
        if (mouseX >= detailX && mouseX <= detailX + detailWidth
                && mouseY >= profileListY && mouseY <= profileListY + profileListHeight) {
            profileScroll -= scrollY * 18.0;
            double max = Math.max(0, availableProfiles.size() * 26 - profileListHeight);
            profileScroll = Math.max(0, Math.min(profileScroll, max));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, COLOR_BACKGROUND);
        graphics.renderOutline(panelLeft, panelTop, panelWidth, panelHeight, COLOR_BORDER);

        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1, panelTop + 43, COLOR_HEADER);
        graphics.fill(panelLeft + 1, panelTop + 42, panelLeft + panelWidth - 1, panelTop + 44, COLOR_ACCENT_DARK);
        graphics.fill(panelLeft + 16, panelTop + 12, panelLeft + 20, panelTop + 32,
                serverMode ? COLOR_SERVER_ACCENT : COLOR_ACCENT);

        graphics.fill(listX, listY, listX + listWidth, contentBottom, COLOR_PANEL);
        graphics.renderOutline(listX, listY, listWidth, Math.max(1, contentBottom - listY), COLOR_BORDER);

        graphics.fill(detailX, listY, detailX + detailWidth, contentBottom, COLOR_PANEL);
        graphics.renderOutline(detailX, listY, detailWidth, Math.max(1, contentBottom - listY), COLOR_BORDER);

        graphics.fill(panelLeft + 1, contentBottom + 1, panelLeft + panelWidth - 1, panelTop + panelHeight - 1, COLOR_HEADER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, panelLeft + 29, panelTop + 14, COLOR_TEXT, false);
        String countStr = (serverMode ? "SERVIDOR • " : "") + containers.size() + " contenedores";
        graphics.drawString(font, countStr,
                panelLeft + panelWidth - 16 - font.width(countStr),
                panelTop + 14, serverMode ? COLOR_SERVER_ACCENT : COLOR_MUTED, false);

        renderContainerList(graphics, mouseX, mouseY);
        ItemStack hoveredItem = renderContainerDetails(graphics, mouseX, mouseY);

        drawButton(graphics, originalModeBtn, mouseX, mouseY);
        drawButton(graphics, randomModeBtn, mouseX, mouseY);
        drawButton(graphics, perPlayerBtn, mouseX, mouseY);
        drawButton(graphics, applyAllBtn, mouseX, mouseY);
        drawButton(graphics, editProfilesBtn, mouseX, mouseY);
        drawButton(graphics, backBtn, mouseX, mouseY);
        drawButton(graphics, saveBtn, mouseX, mouseY);

        int statusMaxW = Math.max(1, backBtn.x - (panelLeft + 16) - 10);
        graphics.drawString(font, font.plainSubstrByWidth(statusMessage, statusMaxW),
                panelLeft + 16, panelTop + panelHeight - 24, statusColor, false);

        if (hoveredItem != null && !hoveredItem.isEmpty()) {
            graphics.renderTooltip(font, hoveredItem, mouseX, mouseY);
        }
    }

    private void renderContainerList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (containers.isEmpty()) {
            graphics.drawWordWrap(font,
                    Component.literal("Esta estructura no contiene cofres, barriles ni shulkers."),
                    listX + 12, listY + 14, Math.max(1, listWidth - 24), COLOR_MUTED);
            return;
        }

        graphics.enableScissor(listX + 1, listY + 1, listX + listWidth - 1, contentBottom - 1);
        for (int i = 0; i < containers.size(); i++) {
            int y = (int) (listY + i * ENTRY_HEIGHT - containerScroll);
            if (y + ENTRY_HEIGHT < listY || y > contentBottom) {
                continue;
            }
            ContainerEntry c = containers.get(i);
            boolean isSel = selected == c;
            boolean hovered = mouseX >= listX && mouseX <= listX + listWidth
                    && mouseY >= Math.max(y, listY) && mouseY <= Math.min(y + ENTRY_HEIGHT, contentBottom);

            int rowL = listX + 5;
            int rowR = listX + listWidth - 7;
            if (isSel) {
                graphics.fill(rowL, y + 3, rowR, y + ENTRY_HEIGHT - 3, 0xFF1B3A2B);
                graphics.fill(rowL, y + 3, rowL + 3, y + ENTRY_HEIGHT - 3, COLOR_ACCENT);
                graphics.renderOutline(rowL, y + 3, Math.max(1, rowR - rowL), ENTRY_HEIGHT - 6, 0xFF3E8C65);
            } else if (hovered) {
                graphics.fill(rowL, y + 3, rowR, y + ENTRY_HEIGHT - 3, 0xFF162C22);
            }

            int availW = Math.max(1, rowR - rowL - 16);
            String titleLine = "#" + (i + 1) + " " + c.blockName + "  (" + c.x + ", " + c.y + ", " + c.z + ")";
            graphics.drawString(font, font.plainSubstrByWidth(titleLine, availW),
                    rowL + 10, y + 8, isSel ? COLOR_ACCENT_LIGHT : COLOR_TEXT, false);

            String sub;
            int subColor;
            if (c.mode == LootMode.RANDOM_LOOT) {
                String pName = findProfileName(c.profileId);
                sub = "Loot: " + (pName != null ? pName : c.profileId);
                subColor = COLOR_GOLD;
            } else {
                sub = "Original (" + c.originalItems.size() + " ítems)";
                subColor = c.perPlayer ? COLOR_SERVER_ACCENT : COLOR_MUTED;
            }
            if (c.perPlayer) {
                sub += " • Por jugador";
            }
            graphics.drawString(font, font.plainSubstrByWidth(sub, availW),
                    rowL + 10, y + 23, subColor, false);
        }
        graphics.disableScissor();
    }

    private ItemStack renderContainerDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = detailX + 12;
        if (selected == null) {
            graphics.drawCenteredString(font, "Sin contenedores para configurar.",
                    detailX + detailWidth / 2, listY + 60, COLOR_MUTED);
            return ItemStack.EMPTY;
        }

        graphics.drawString(font,
                font.plainSubstrByWidth(selected.blockName + " en (" + selected.x + ", " + selected.y + ", " + selected.z + ")",
                        Math.max(1, detailWidth - 24)),
                x, listY + 10, COLOR_ACCENT, false);

        graphics.drawString(font, "Contenido original guardado:", x, listY + 26, COLOR_MUTED, false);
        int previewBoxY = listY + 38;
        graphics.fill(x, previewBoxY, detailX + detailWidth - 12, previewBoxY + 26, COLOR_PANEL_INNER);
        graphics.renderOutline(x, previewBoxY, Math.max(1, detailWidth - 24), 26, COLOR_BORDER);

        ItemStack hoveredItem = ItemStack.EMPTY;
        if (selected.originalItems.isEmpty()) {
            graphics.drawString(font, "(Vacío originalmente)", x + 8, previewBoxY + 9, COLOR_MUTED, false);
        } else {
            int maxIcons = Math.max(1, (detailWidth - 36) / 20);
            int shown = Math.min(maxIcons, selected.originalItems.size());
            for (int i = 0; i < shown; i++) {
                ItemStack stack = selected.originalItems.get(i);
                int ix = x + 6 + i * 20;
                int iy = previewBoxY + 5;
                graphics.renderItem(stack, ix, iy);
                graphics.renderItemDecorations(font, stack, ix, iy);
                if (mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16) {
                    hoveredItem = stack;
                }
            }
        }

        graphics.drawString(font, "Modo al colocar:", x, listY + 72, COLOR_TEXT, false);

        if (selected.mode == LootMode.ORIGINAL) {
            graphics.fill(x, profileListY, detailX + detailWidth - 12, profileListY + profileListHeight, COLOR_PANEL_INNER);
            graphics.renderOutline(x, profileListY, Math.max(1, detailWidth - 24), profileListHeight, COLOR_BORDER);
            String msg = selected.perPlayer
                    ? "Cada jugador que abra este cofre recibirá su propia copia de los ítems originales."
                    : "Este cofre conservará exactamente los ítems que tenía cuando guardaste la estructura (compartido).";
            graphics.drawWordWrap(font, Component.literal(msg),
                    x + 12, profileListY + 14, Math.max(1, detailWidth - 48), COLOR_MUTED);
        } else {
            graphics.drawString(font, "Elige el perfil de loot:", x, profileListY - 11, COLOR_ACCENT_LIGHT, false);
            graphics.fill(x, profileListY, detailX + detailWidth - 12, profileListY + profileListHeight, COLOR_PANEL_INNER);
            graphics.renderOutline(x, profileListY, Math.max(1, detailWidth - 24), profileListHeight, COLOR_BORDER);

            graphics.enableScissor(x + 1, profileListY + 1, detailX + detailWidth - 13, profileListY + profileListHeight - 1);
            int rowH = 26;
            for (int i = 0; i < availableProfiles.size(); i++) {
                int py = (int) (profileListY + i * rowH - profileScroll);
                if (py + rowH < profileListY || py > profileListY + profileListHeight) {
                    continue;
                }
                LootProfile p = availableProfiles.get(i);
                boolean chosen = p.id.equalsIgnoreCase(selected.profileId);
                boolean hov = mouseX >= x && mouseX <= detailX + detailWidth - 12
                        && mouseY >= Math.max(py, profileListY)
                        && mouseY <= Math.min(py + rowH, profileListY + profileListHeight);

                if (chosen) {
                    graphics.fill(x + 3, py + 2, detailX + detailWidth - 15, py + rowH - 2, 0xFF1E4C35);
                    graphics.renderOutline(x + 3, py + 2, Math.max(1, detailWidth - 30), rowH - 4, COLOR_ACCENT);
                } else if (hov) {
                    graphics.fill(x + 3, py + 2, detailX + detailWidth - 15, py + rowH - 2, 0xFF142A1F);
                }

                String pText = p.name + "  (" + p.entries.size() + " ítems • " + p.minRolls + "-" + p.maxRolls + "/cofre)";
                graphics.drawString(font, font.plainSubstrByWidth(pText, Math.max(1, detailWidth - 46)),
                        x + 10, py + 9, chosen ? COLOR_ACCENT_LIGHT : COLOR_TEXT, false);
            }
            graphics.disableScissor();
        }

        return hoveredItem;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private enum ButtonStyle {
        PRIMARY,
        SECONDARY
    }

    private static final class UiButton {
        final int x, y, width, height;
        final String icon;
        ButtonStyle style;
        Component label;
        boolean active = true;

        UiButton(int x, int y, int width, int height, Component label, String icon, ButtonStyle style) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
            this.icon = icon;
            this.style = style;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private void drawButton(GuiGraphics graphics, UiButton button, int mouseX, int mouseY) {
        if (button == null) return;
        boolean hovered = button.active && button.contains(mouseX, mouseY);

        int bg, border, accent, textCol, iconBg;
        if (!button.active) {
            bg = 0xFF151E19; border = 0xFF2A3B32; accent = 0xFF536E60; textCol = 0xFF667F71; iconBg = 0x33223029;
        } else if (button.style == ButtonStyle.PRIMARY) {
            bg = hovered ? 0xFF297550 : 0xFF1E563B; border = hovered ? COLOR_ACCENT_LIGHT : COLOR_ACCENT;
            accent = COLOR_ACCENT_LIGHT; textCol = COLOR_TEXT; iconBg = 0x6616422D;
        } else {
            bg = hovered ? 0xFF224233 : 0xFF172E23; border = hovered ? COLOR_ACCENT : COLOR_BORDER;
            accent = COLOR_ACCENT; textCol = COLOR_TEXT; iconBg = 0x661E3D2E;
        }

        graphics.fill(button.x, button.y + 3, button.x + button.width, button.y + button.height + 3, 0x99000000);
        graphics.fill(button.x, button.y, button.x + button.width, button.y + button.height, bg);
        graphics.renderOutline(button.x, button.y, button.width, button.height, border);
        if (button.width > 5 && button.height > 4) {
            graphics.fill(button.x + 2, button.y + 2, button.x + button.width - 2, button.y + 4, accent);
        }
        if (button.width >= 25 && button.height >= 12) {
            int iconL = button.x + 5;
            graphics.fill(iconL, button.y + 5, iconL + 17, button.y + button.height - 5, iconBg);
            graphics.drawCenteredString(font, button.icon, iconL + 8, button.y + button.height / 2 - 4, accent);
        }
        String text = font.plainSubstrByWidth(button.label.getString(), Math.max(1, button.width - 30));
        graphics.drawCenteredString(font, text,
                button.x + button.width / 2 + (button.width >= 25 ? 7 : 0),
                button.y + button.height / 2 - 4, textCol);
    }

    private void playClickSound() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}