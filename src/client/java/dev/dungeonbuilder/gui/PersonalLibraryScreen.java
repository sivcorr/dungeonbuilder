package dev.dungeonbuilder.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dungeonbuilder.network.ModNetworking.RequestUndoPayload;
import dev.dungeonbuilder.placement.ClientUndoState;
import dev.dungeonbuilder.preview.PreviewManager;
import dev.dungeonbuilder.storage.LocalLibrary;
import dev.dungeonbuilder.storage.ThumbnailGenerator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Biblioteca personal local de DungeonBuilder ("Estructuras mías"). */
public class PersonalLibraryScreen extends Screen {

    private static final int ENTRY_HEIGHT = 52;

    private static final int COLOR_BACKGROUND = 0xF00B1611;
    private static final int COLOR_HEADER = 0xFF132A1F;
    private static final int COLOR_PANEL = 0xF0101F18;
    private static final int COLOR_PANEL_INNER = 0xFF0C1812;

    private static final int COLOR_ACCENT = 0xFF4ADE80;
    private static final int COLOR_ACCENT_LIGHT = 0xFFA7F3D0;
    private static final int COLOR_ACCENT_DARK = 0xFF256D49;

    private static final int COLOR_TEXT = 0xFFEEFBF3;
    private static final int COLOR_MUTED = 0xFF93B3A1;
    private static final int COLOR_BORDER = 0xFF2B5942;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.systemDefault());

    private List<LocalLibrary.Entry> allEntries = List.of();
    private List<LocalLibrary.Entry> filteredEntries = List.of();

    private LocalLibrary.Entry selected;
    private ResourceLocation selectedThumbTexture;

    private EditBox searchBox;

    private double scrollOffset;
    private long confirmDeleteUntilMs;
    private long confirmPlaceUntilMs;
    private long confirmUndoUntilMs;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;

    private int detailX;
    private int detailWidth;
    private int contentBottom;
    private int actionY;

    private UiButton reloadButton;
    private UiButton previewButton;
    private UiButton placeButton;
    private UiButton lootButton;
    private UiButton deleteButton;
    private UiButton undoButton;
    private UiButton closeButton;

    public PersonalLibraryScreen() {
        super(Component.translatable("gui.dungeonbuilder.personal.title"));
    }

    @Override
    protected void init() {
        panelWidth = Math.max(1, Math.min(900, width - 12));
        panelHeight = Math.max(1, Math.min(440, height - 12));

        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int contentWidth = Math.max(1, panelWidth - 32);

        listX = panelLeft + 16;
        listY = panelTop + 78;

        listWidth = Math.max(1, (int) (contentWidth * 0.42));
        detailX = listX + listWidth + 12;
        detailWidth = Math.max(1, panelLeft + panelWidth - 16 - detailX);

        contentBottom = panelTop + panelHeight - 34;
        listHeight = Math.max(1, contentBottom - listY);
        actionY = Math.max(listY, contentBottom - 53);

        String previousQuery = searchBox != null ? searchBox.getValue() : "";

        int reloadWidth = Math.min(88, Math.max(48, listWidth / 3));
        int searchWidth = Math.max(1, listWidth - reloadWidth - 8);

        searchBox = new EditBox(
                font,
                listX,
                panelTop + 48,
                searchWidth,
                23,
                Component.translatable("gui.dungeonbuilder.personal.search_hint")
        );
        searchBox.setMaxLength(48);
        searchBox.setHint(Component.translatable("gui.dungeonbuilder.personal.search_hint"));
        searchBox.setValue(previousQuery);
        searchBox.setResponder(query -> applyFilter());
        addRenderableWidget(searchBox);

        reloadButton = new UiButton(
                listX + listWidth - reloadWidth,
                panelTop + 47,
                reloadWidth,
                25,
                Component.translatable("gui.dungeonbuilder.common.reload"),
                "R",
                ButtonStyle.SECONDARY
        );

        int buttonGap = 8;
        int actionWidth = Math.max(1, (detailWidth - 20 - buttonGap) / 2);

        previewButton = new UiButton(
                detailX + 10,
                actionY,
                actionWidth,
                22,
                Component.translatable("gui.dungeonbuilder.personal.action.preview"),
                ">",
                ButtonStyle.PRIMARY
        );

        placeButton = new UiButton(
                detailX + 10 + actionWidth + buttonGap,
                actionY,
                actionWidth,
                22,
                Component.translatable("gui.dungeonbuilder.personal.action.place"),
                "+",
                ButtonStyle.SECONDARY
        );

        lootButton = new UiButton(
                detailX + 10,
                actionY + 26,
                actionWidth,
                21,
                Component.literal("Cofres / Loot"),
                "C",
                ButtonStyle.SECONDARY
        );

        deleteButton = new UiButton(
                detailX + 10 + actionWidth + buttonGap,
                actionY + 26,
                actionWidth,
                21,
                Component.translatable("gui.dungeonbuilder.common.delete"),
                "X",
                ButtonStyle.DANGER
        );

        int footerY = panelTop + panelHeight - 28;
        closeButton = new UiButton(
                panelLeft + panelWidth - 88,
                footerY,
                76,
                21,
                Component.translatable("gui.dungeonbuilder.common.close"),
                "X",
                ButtonStyle.SECONDARY
        );

        undoButton = new UiButton(
                closeButton.x - 6 - 100,
                footerY,
                100,
                21,
                Component.literal("Deshacer"),
                "<",
                ButtonStyle.DANGER
        );
        refreshUndoButton();

        reloadFromDisk();
    }

    public void refreshUndoButton() {
        confirmUndoUntilMs = 0;
        if (undoButton != null) {
            undoButton.active = ClientUndoState.isAvailable();
            undoButton.label = Component.literal("Deshacer");
        }
    }

    private void reloadFromDisk() {
        String previousId = selected != null ? selected.id : null;
        allEntries = LocalLibrary.listStructures();
        applyFilter();

        if (previousId != null) {
            selectEntry(filteredEntries.stream()
                    .filter(entry -> previousId.equals(entry.id))
                    .findFirst()
                    .orElse(filteredEntries.isEmpty() ? null : filteredEntries.get(0)));
        } else {
            selectEntry(filteredEntries.isEmpty() ? null : filteredEntries.get(0));
        }
    }

    private void applyFilter() {
        String query = searchBox != null
                ? searchBox.getValue().trim().toLowerCase(Locale.ROOT)
                : "";

        if (query.isEmpty()) {
            filteredEntries = new ArrayList<>(allEntries);
        } else {
            List<LocalLibrary.Entry> matches = new ArrayList<>();
            for (LocalLibrary.Entry entry : allEntries) {
                String name = entry.name == null ? "" : entry.name;
                String id = entry.id == null ? "" : entry.id;
                if (name.toLowerCase(Locale.ROOT).contains(query)
                        || id.toLowerCase(Locale.ROOT).contains(query)) {
                    matches.add(entry);
                }
            }
            filteredEntries = matches;
        }

        clampScroll();

        if (selected != null && !filteredEntries.contains(selected)) {
            selectEntry(filteredEntries.isEmpty() ? null : filteredEntries.get(0));
        } else {
            updateActionButtons();
        }
    }

    private void selectEntry(LocalLibrary.Entry entry) {
        selected = entry;
        selectedThumbTexture = entry != null
                ? ThumbnailGenerator.getOrCreateTexture(entry.id)
                : null;
        resetConfirmations();
        updateActionButtons();
    }

    private void handlePreviewClick() {
        if (selected == null) {
            return;
        }
        String structureId = selected.id;
        onClose();
        PreviewManager.startPreview(structureId);
    }

    private void handlePlaceClick() {
        if (selected == null || minecraft == null || minecraft.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now > confirmPlaceUntilMs) {
            confirmPlaceUntilMs = now + 3500L;
            placeButton.label = Component.translatable("gui.dungeonbuilder.personal.action.confirm_place");
            return;
        }

        String structureId = selected.id;
        var position = minecraft.player.blockPosition();

        onClose();
        PreviewManager.clear();
        PreviewManager.placeLocalStructureNow(structureId, position, 0);
    }

    private void handleLootClick() {
        if (selected == null || minecraft == null) {
            return;
        }
        String structureId = selected.id;
        String structureName = selected.name == null ? selected.id : selected.name;
        minecraft.setScreen(new ChestLootConfigScreen(this, structureId, structureName));
    }

    private void handleDeleteClick() {
        if (selected == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now > confirmDeleteUntilMs) {
            confirmDeleteUntilMs = now + 3500L;
            deleteButton.label = Component.translatable("gui.dungeonbuilder.common.confirm_delete");
            return;
        }

        String structureId = selected.id;
        LocalLibrary.deleteStructure(structureId);
        ThumbnailGenerator.invalidate(structureId);

        selected = null;
        selectedThumbTexture = null;
        reloadFromDisk();
    }

    private void handleUndoClick() {
        if (!ClientUndoState.isAvailable()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now > confirmUndoUntilMs) {
            confirmUndoUntilMs = now + 3500L;
            undoButton.label = Component.literal("¿Deshacer?");
            return;
        }

        onClose();
        ClientPlayNetworking.send(new RequestUndoPayload());
    }

    private void resetConfirmations() {
        confirmDeleteUntilMs = 0;
        confirmPlaceUntilMs = 0;
        confirmUndoUntilMs = 0;

        if (deleteButton != null) {
            deleteButton.label = Component.translatable("gui.dungeonbuilder.common.delete");
        }
        if (placeButton != null) {
            placeButton.label = Component.translatable("gui.dungeonbuilder.personal.action.place");
        }
        if (undoButton != null) {
            undoButton.label = Component.literal("Deshacer");
        }
    }

    private void updateActionButtons() {
        boolean hasSelection = selected != null;
        if (previewButton != null) previewButton.active = hasSelection;
        if (placeButton != null) placeButton.active = hasSelection;
        if (lootButton != null) lootButton.active = hasSelection && selected.blockEntityCount > 0;
        if (deleteButton != null) deleteButton.active = hasSelection;
    }

    @Override
    public void tick() {
        super.tick();

        long now = System.currentTimeMillis();
        if (confirmDeleteUntilMs > 0 && now > confirmDeleteUntilMs) {
            confirmDeleteUntilMs = 0;
            if (deleteButton != null) {
                deleteButton.label = Component.translatable("gui.dungeonbuilder.common.delete");
            }
        }

        if (confirmPlaceUntilMs > 0 && now > confirmPlaceUntilMs) {
            confirmPlaceUntilMs = 0;
            if (placeButton != null) {
                placeButton.label = Component.translatable("gui.dungeonbuilder.personal.action.place");
            }
        }

        if (confirmUndoUntilMs > 0 && now > confirmUndoUntilMs) {
            confirmUndoUntilMs = 0;
            if (undoButton != null) {
                undoButton.label = Component.literal("Deshacer");
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (button != 0) {
            return false;
        }

        if (reloadButton.contains(mouseX, mouseY)) {
            playClickSound();
            reloadFromDisk();
            return true;
        }

        if (previewButton.contains(mouseX, mouseY)) {
            if (previewButton.active) {
                playClickSound();
                handlePreviewClick();
            }
            return true;
        }

        if (placeButton.contains(mouseX, mouseY)) {
            if (placeButton.active) {
                playClickSound();
                handlePlaceClick();
            }
            return true;
        }

        if (lootButton.contains(mouseX, mouseY)) {
            if (lootButton.active) {
                playClickSound();
                handleLootClick();
            }
            return true;
        }

        if (deleteButton.contains(mouseX, mouseY)) {
            if (deleteButton.active) {
                playClickSound();
                handleDeleteClick();
            }
            return true;
        }

        if (ClientUndoState.isAvailable() && undoButton != null && undoButton.contains(mouseX, mouseY)) {
            playClickSound();
            handleUndoClick();
            return true;
        }

        if (closeButton.contains(mouseX, mouseY)) {
            playClickSound();
            onClose();
            return true;
        }

        if (mouseX >= listX && mouseX <= listX + listWidth
                && mouseY >= listY && mouseY <= listY + listHeight) {
            int index = (int) ((mouseY - listY + scrollOffset) / ENTRY_HEIGHT);
            if (index >= 0 && index < filteredEntries.size()) {
                playClickSound();
                selectEntry(filteredEntries.get(index));
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listWidth
                && mouseY >= listY && mouseY <= listY + listHeight) {
            scrollOffset -= scrollY * 20.0;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void clampScroll() {
        double contentHeight = filteredEntries.size() * ENTRY_HEIGHT;
        double maxScroll = Math.max(0, contentHeight - listHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, COLOR_BACKGROUND);
        graphics.renderOutline(panelLeft, panelTop, panelWidth, panelHeight, COLOR_BORDER);

        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1, panelTop + 43, COLOR_HEADER);
        graphics.fill(panelLeft + 1, panelTop + 42, panelLeft + panelWidth - 1, panelTop + 44, COLOR_ACCENT_DARK);
        graphics.fill(panelLeft + 16, panelTop + 12, panelLeft + 20, panelTop + 32, COLOR_ACCENT);

        graphics.fill(listX, panelTop + 47, listX + listWidth, panelTop + 73, COLOR_PANEL_INNER);

        graphics.fill(detailX, panelTop + 47, detailX + detailWidth, panelTop + 72, COLOR_PANEL_INNER);
        graphics.renderOutline(detailX, panelTop + 47, detailWidth, 25, COLOR_BORDER);

        graphics.fill(listX, listY, listX + listWidth, contentBottom, COLOR_PANEL);
        graphics.renderOutline(listX, listY, listWidth, Math.max(1, contentBottom - listY), COLOR_BORDER);

        graphics.fill(detailX, listY, detailX + detailWidth, contentBottom, COLOR_PANEL);
        graphics.renderOutline(detailX, listY, detailWidth, Math.max(1, contentBottom - listY), COLOR_BORDER);

        graphics.fill(panelLeft + 1, contentBottom + 1, panelLeft + panelWidth - 1, panelTop + panelHeight - 1, COLOR_HEADER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        renderHeader(graphics);
        renderTopBadge(graphics);
        renderList(graphics, mouseX, mouseY);
        renderDetails(graphics);

        drawButton(graphics, reloadButton, mouseX, mouseY);
        drawButton(graphics, previewButton, mouseX, mouseY);
        drawButton(graphics, placeButton, mouseX, mouseY);
        drawButton(graphics, lootButton, mouseX, mouseY);
        drawButton(graphics, deleteButton, mouseX, mouseY);

        int leftMostRightButton = closeButton.x;
        if (ClientUndoState.isAvailable() && undoButton != null) {
            drawButton(graphics, undoButton, mouseX, mouseY);
            leftMostRightButton = undoButton.x;
        }
        drawButton(graphics, closeButton, mouseX, mouseY);

        String folderTextRaw = ClientUndoState.isAvailable()
                ? "Deshacer disponible: " + ClientUndoState.structureName()
                : Component.translatable("gui.dungeonbuilder.personal.folder_hint").getString();
        int folderMaxWidth = Math.max(1, leftMostRightButton - (panelLeft + 16) - 10);
        String folderText = font.plainSubstrByWidth(folderTextRaw, folderMaxWidth);

        graphics.drawString(font, folderText, panelLeft + 16, panelTop + panelHeight - 20, COLOR_MUTED, false);
    }

    private void renderHeader(GuiGraphics graphics) {
        graphics.drawString(font, title, panelLeft + 29, panelTop + 14, COLOR_TEXT, false);

        Component count = Component.translatable("gui.dungeonbuilder.personal.count", allEntries.size());
        graphics.drawString(font, count,
                panelLeft + panelWidth - 16 - font.width(count),
                panelTop + 14, COLOR_MUTED, false);
    }

    private void renderTopBadge(GuiGraphics graphics) {
        int maxW = Math.max(1, detailWidth - 16);
        String label = "BIBLIOTECA LOCAL • ALMACENAMIENTO PERSONAL";
        graphics.drawString(font, font.plainSubstrByWidth(label, maxW),
                detailX + 10, panelTop + 56, COLOR_ACCENT_LIGHT, false);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (filteredEntries.isEmpty()) {
            Component emptyMessage = allEntries.isEmpty()
                    ? Component.translatable("gui.dungeonbuilder.personal.empty")
                    : Component.translatable("gui.dungeonbuilder.personal.no_results");

            graphics.drawWordWrap(font, emptyMessage,
                    listX + 10, listY + 12,
                    Math.max(1, listWidth - 20), COLOR_MUTED);
            return;
        }

        graphics.enableScissor(listX + 1, listY + 1, listX + listWidth - 1, contentBottom - 1);

        for (int index = 0; index < filteredEntries.size(); index++) {
            int y = (int) (listY + index * ENTRY_HEIGHT - scrollOffset);
            if (y + ENTRY_HEIGHT < listY || y > contentBottom) {
                continue;
            }

            LocalLibrary.Entry entry = filteredEntries.get(index);
            boolean isSelected = selected != null && selected.id.equals(entry.id);
            boolean isHovered = mouseX >= listX && mouseX <= listX + listWidth
                    && mouseY >= Math.max(y, listY)
                    && mouseY <= Math.min(y + ENTRY_HEIGHT, contentBottom);

            int rowLeft = listX + 5;
            int rowRight = listX + listWidth - 7;

            if (isSelected) {
                graphics.fill(rowLeft, y + 3, rowRight, y + ENTRY_HEIGHT - 3, 0xFF1B3A2B);
                graphics.fill(rowLeft, y + 3, rowLeft + 3, y + ENTRY_HEIGHT - 3, COLOR_ACCENT);
                graphics.renderOutline(rowLeft, y + 3,
                        Math.max(1, rowRight - rowLeft), ENTRY_HEIGHT - 6, 0xFF3E8C65);
            } else if (isHovered) {
                graphics.fill(rowLeft, y + 3, rowRight, y + ENTRY_HEIGHT - 3, 0xFF162C22);
            }

            int iconX = listX + 13;
            int iconY = y + 9;
            graphics.fill(iconX, iconY, iconX + 34, iconY + 34, isSelected ? 0xFF23523B : 0xFF183125);
            graphics.renderOutline(iconX, iconY, 34, 34, isSelected ? COLOR_ACCENT : COLOR_BORDER);
            graphics.drawCenteredString(font, "D", iconX + 17, iconY + 13, COLOR_ACCENT_LIGHT);

            int textX = iconX + 44;
            int availableWidth = Math.max(1, listWidth - (textX - listX) - 15);

            String name = entry.name == null ? entry.id : entry.name;
            graphics.drawString(font,
                    font.plainSubstrByWidth(name, availableWidth),
                    textX, y + 9,
                    isSelected ? COLOR_ACCENT_LIGHT : COLOR_TEXT,
                    false);

            String dimensions = entry.width + " × " + entry.height + " × " + entry.depth;
            graphics.drawString(font,
                    font.plainSubstrByWidth(dimensions, availableWidth),
                    textX, y + 26, COLOR_MUTED, false);
        }

        graphics.disableScissor();

        int totalContentHeight = filteredEntries.size() * ENTRY_HEIGHT;
        if (totalContentHeight > listHeight && listHeight > 12) {
            int trackX = listX + listWidth - 4;
            graphics.fill(trackX, listY + 5, trackX + 2, contentBottom - 5, 0xFF1A3327);

            int barHeight = Math.max(18, (int) ((double) listHeight * listHeight / totalContentHeight));
            barHeight = Math.min(barHeight, Math.max(1, listHeight - 10));

            double maxScroll = Math.max(1, totalContentHeight - listHeight);
            double ratio = scrollOffset / maxScroll;
            int barY = listY + 5 + (int) (ratio * Math.max(0, listHeight - barHeight - 10));

            graphics.fill(trackX, barY, trackX + 2, barY + barHeight, COLOR_ACCENT);
        }
    }

    private void renderDetails(GuiGraphics graphics) {
        int x = detailX + 13;
        int maxWidth = Math.max(1, detailWidth - 26);
        int headingY = listY + 12;

        graphics.drawString(font, "DETALLES DE LA ESTRUCTURA", x, headingY, COLOR_ACCENT, false);

        if (selected == null) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.dungeonbuilder.personal.select_hint"),
                    detailX + detailWidth / 2,
                    listY + 70,
                    COLOR_MUTED);
            return;
        }

        int contentY = listY + 36;
        int availableForContent = Math.max(1, actionY - contentY - 7);

        int thumbSize = Math.max(1, Math.min(96, Math.min(detailWidth / 3, availableForContent)));
        int thumbX = detailX + detailWidth - thumbSize - 12;
        int thumbY = contentY;

        renderThumbnail(graphics, thumbX, thumbY, thumbSize);

        int textWidth = Math.max(1, thumbX - x - 9);
        boolean compact = detailWidth < 300 || availableForContent < 92;

        String structureName = selected.name == null ? selected.id : selected.name;
        drawClippedLine(graphics, structureName, x, contentY, COLOR_ACCENT_LIGHT, textWidth);

        if (!compact && availableForContent >= 36) {
            drawClippedLine(graphics, selected.id + ".dungeon", x, contentY + 17, COLOR_MUTED, textWidth);
        }

        int dimensionsY = contentY + (compact ? 17 : 38);
        if (availableForContent >= (compact ? 30 : 48)) {
            String dimensions = "Dimensiones: " + selected.width + " × " + selected.height + " × " + selected.depth;
            drawClippedLine(graphics, dimensions, x, dimensionsY, COLOR_TEXT, textWidth);
        }

        int blocksY = dimensionsY + 18;
        if (availableForContent >= (compact ? 48 : 66)) {
            String blocks = Component.translatable(
                    "gui.dungeonbuilder.personal.detail.blocks",
                    selected.nonAirBlocks,
                    selected.volume()
            ).getString();
            drawClippedLine(graphics, blocks, x, blocksY, COLOR_TEXT, textWidth);
        }

        int blockEntitiesY = blocksY + 18;
        if (!compact && availableForContent >= 84) {
            String blockEntities = Component.translatable(
                    "gui.dungeonbuilder.personal.detail.block_entities",
                    selected.blockEntityCount
            ).getString();
            drawClippedLine(graphics, blockEntities, x, blockEntitiesY, COLOR_TEXT, textWidth);
        }

        int metadataY = Math.max(contentY + (compact ? 52 : 86), thumbY + thumbSize + 7);
        int metadataAvailable = actionY - metadataY - 5;

        if (metadataAvailable >= 13) {
            graphics.fill(detailX + 13, metadataY - 5, detailX + detailWidth - 13, metadataY - 4, COLOR_BORDER);
            String author = Component.translatable(
                    "gui.dungeonbuilder.personal.detail.author",
                    selected.author == null ? "-" : selected.author
            ).getString();
            drawClippedLine(graphics, author, x, metadataY, COLOR_MUTED, maxWidth);
        }

        if (metadataAvailable >= 29) {
            String date = Component.translatable(
                    "gui.dungeonbuilder.personal.detail.date",
                    DATE_FORMAT.format(Instant.ofEpochMilli(selected.createdAt))
            ).getString();
            drawClippedLine(graphics, date, x, metadataY + 15, COLOR_MUTED, maxWidth);
        }

        if (metadataAvailable >= 45) {
            String size = Component.translatable(
                    "gui.dungeonbuilder.personal.detail.size",
                    formatBytes(selected.fileSizeBytes)
            ).getString();
            drawClippedLine(graphics, size, x, metadataY + 30, COLOR_MUTED, maxWidth);
        }

        if (metadataAvailable >= 61) {
            List<String> missingMods = findMissingMods(selected.requiredMods);
            String mods = selected.requiredMods == null || selected.requiredMods.isEmpty()
                    ? "minecraft"
                    : String.join(", ", selected.requiredMods);

            String modsText = Component.translatable("gui.dungeonbuilder.personal.detail.mods", mods).getString();
            drawClippedLine(graphics, modsText,
                    x, metadataY + 45,
                    missingMods.isEmpty() ? COLOR_ACCENT_LIGHT : 0xFFFF9999,
                    maxWidth);

            if (!missingMods.isEmpty() && metadataAvailable >= 77) {
                String missingText = Component.translatable(
                        "gui.dungeonbuilder.personal.detail.missing_mods",
                        String.join(", ", missingMods)
                ).getString();
                drawClippedLine(graphics, missingText, x, metadataY + 60, 0xFFFF9999, maxWidth);
            }
        }
    }

    private void renderThumbnail(GuiGraphics graphics, int x, int y, int size) {
        if (size <= 0) {
            return;
        }
        graphics.fill(x, y, x + size, y + size, COLOR_PANEL_INNER);
        graphics.renderOutline(x, y, size, size, COLOR_BORDER);

        if (selectedThumbTexture != null && size >= 5) {
            int innerSize = size - 4;
            RenderSystem.enableBlend();
            graphics.blit(selectedThumbTexture,
                    x + 2, y + 2,
                    0, 0,
                    innerSize, innerSize,
                    innerSize, innerSize);
            RenderSystem.disableBlend();
        } else {
            graphics.drawCenteredString(font, "D", x + size / 2, y + size / 2 - 4, COLOR_ACCENT_LIGHT);
        }
    }

    private void drawClippedLine(GuiGraphics graphics, String text, int x, int y, int color, int maxWidth) {
        if (maxWidth <= 0) {
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(text, maxWidth), x, y, color, false);
    }

    private enum ButtonStyle {
        PRIMARY,
        SECONDARY,
        DANGER
    }

    private static final class UiButton {
        final int x, y, width, height;
        final String icon;
        final ButtonStyle style;
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
        if (button == null) {
            return;
        }

        boolean hovered = button.active && button.contains(mouseX, mouseY);

        int background;
        int border;
        int accent;
        int textColor;
        int iconBoxBg;

        if (!button.active) {
            background = 0xFF151E19;
            border = 0xFF2A3B32;
            accent = 0xFF536E60;
            textColor = 0xFF667F71;
            iconBoxBg = 0x33223029;
        } else if (button.style == ButtonStyle.PRIMARY) {
            background = hovered ? 0xFF297550 : 0xFF1E563B;
            border = hovered ? COLOR_ACCENT_LIGHT : COLOR_ACCENT;
            accent = COLOR_ACCENT_LIGHT;
            textColor = COLOR_TEXT;
            iconBoxBg = 0x6616422D;
        } else if (button.style == ButtonStyle.DANGER) {
            background = hovered ? 0xFF6A2E3B : 0xFF42232B;
            border = hovered ? 0xFFFF9FB0 : 0xFF9B5565;
            accent = 0xFFFF9FB0;
            textColor = COLOR_TEXT;
            iconBoxBg = 0x664A1E28;
        } else {
            background = hovered ? 0xFF224233 : 0xFF172E23;
            border = hovered ? COLOR_ACCENT : COLOR_BORDER;
            accent = COLOR_ACCENT;
            textColor = COLOR_TEXT;
            iconBoxBg = 0x661E3D2E;
        }

        graphics.fill(button.x, button.y + 3, button.x + button.width, button.y + button.height + 3, 0x99000000);
        graphics.fill(button.x, button.y, button.x + button.width, button.y + button.height, background);
        graphics.renderOutline(button.x, button.y, button.width, button.height, border);

        if (button.width > 5 && button.height > 4) {
            graphics.fill(button.x + 2, button.y + 2, button.x + button.width - 2, button.y + 4, accent);
        }

        if (button.width >= 25 && button.height >= 12) {
            int iconLeft = button.x + 5;
            graphics.fill(iconLeft, button.y + 5, iconLeft + 17, button.y + button.height - 5, iconBoxBg);
            graphics.drawCenteredString(font, button.icon, iconLeft + 8, button.y + button.height / 2 - 4, accent);
        }

        int availableTextWidth = Math.max(1, button.width - 34);
        String text = font.plainSubstrByWidth(button.label.getString(), availableTextWidth);

        graphics.drawCenteredString(font, text,
                button.x + button.width / 2 + (button.width >= 25 ? 7 : 0),
                button.y + button.height / 2 - 4,
                textColor);
    }

    private void playClickSound() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static List<String> findMissingMods(List<String> requiredMods) {
        if (requiredMods == null || requiredMods.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        FabricLoader loader = FabricLoader.getInstance();
        for (String modId : requiredMods) {
            if (!"minecraft".equals(modId) && !loader.isModLoaded(modId)) {
                missing.add(modId);
            }
        }
        return missing;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}