package dev.dungeonbuilder.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.network.ModNetworking;
import dev.dungeonbuilder.network.ModNetworking.ExportChunkPayload;
import dev.dungeonbuilder.storage.LocalLibrary;
import dev.dungeonbuilder.storage.ThumbnailGenerator;
import dev.dungeonbuilder.structure.StructureCapture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Pantalla del Exportador: envía estructuras de la Biblioteca local al servidor. */
public class ExporterScreen extends Screen {

    // ============================================================
    // ESTILO ROJO CARMESÍ (EXPORTADOR)
    // ============================================================

    private static final int ENTRY_HEIGHT = 48;

    private static final int COLOR_BACKGROUND = 0xF0180B0F;
    private static final int COLOR_HEADER = 0xFF31141C;
    private static final int COLOR_PANEL = 0xF0221015;
    private static final int COLOR_PANEL_INNER = 0xFF1A0B10;

    private static final int COLOR_ACCENT = 0xFFF87171;
    private static final int COLOR_ACCENT_LIGHT = 0xFFFECACA;
    private static final int COLOR_ACCENT_DARK = 0xFF8C303D;

    private static final int COLOR_TEXT = 0xFFFDF2F4;
    private static final int COLOR_MUTED = 0xFFBA98A0;
    private static final int COLOR_BORDER = 0xFF612B37;
    private static final int COLOR_SUCCESS = 0xFF91D5A6;
    private static final int COLOR_ERROR = 0xFFFF8585;

    private static final List<String> CATEGORIES = List.of(
            "General", "Dungeons", "Jefes", "Eventos", "Ruinas", "Decoración"
    );

    // ============================================================
    // DATOS
    // ============================================================

    private final boolean canExport;
    private boolean exporting = false;
    private int categoryIndex = 0;

    private List<LocalLibrary.Entry> allEntries = List.of();
    private List<LocalLibrary.Entry> filteredEntries = List.of();
    private LocalLibrary.Entry selected;
    private ResourceLocation selectedThumbTexture;

    private EditBox searchBox;
    private double scrollOffset;

    private String statusMessage = "Selecciona una estructura local para exportarla.";
    private int statusColor = COLOR_MUTED;

    // ============================================================
    // DISTRIBUCIÓN DINÁMICA
    // ============================================================

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

    // ============================================================
    // BOTONES PERSONALIZADOS
    // ============================================================

    private UiButton reloadButton;
    private UiButton categoryButton;
    private UiButton exportButton;
    private UiButton closeButton;

    public ExporterScreen() {
        this(true);
    }

    public ExporterScreen(boolean canExport) {
        super(Component.literal("Exportador de estructuras"));
        this.canExport = canExport;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(1, Math.min(820, width - 16));
        panelHeight = Math.max(1, Math.min(410, height - 16));

        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int contentWidth = Math.max(1, panelWidth - 32);

        listX = panelLeft + 16;
        listY = panelTop + 78;

        listWidth = Math.max(1, (int) (contentWidth * 0.44));
        detailX = listX + listWidth + 12;
        detailWidth = Math.max(1, panelLeft + panelWidth - 16 - detailX);

        contentBottom = panelTop + panelHeight - 36;
        listHeight = Math.max(1, contentBottom - listY);
        actionY = Math.max(listY, contentBottom - 34);

        String previousQuery = searchBox != null ? searchBox.getValue() : "";

        int reloadWidth = Math.min(92, Math.max(52, listWidth / 3));
        int searchWidth = Math.max(1, listWidth - reloadWidth - 8);

        searchBox = new EditBox(
                font,
                listX,
                panelTop + 48,
                searchWidth,
                23,
                Component.literal("Buscar estructura")
        );
        searchBox.setMaxLength(48);
        searchBox.setHint(Component.literal("Buscar en Biblioteca local..."));
        searchBox.setValue(previousQuery);
        searchBox.setResponder(query -> applyFilter());
        addRenderableWidget(searchBox);

        reloadButton = new UiButton(
                listX + listWidth - reloadWidth,
                panelTop + 47,
                reloadWidth,
                25,
                Component.literal("Recargar"),
                "R",
                ButtonStyle.SECONDARY
        );

        int buttonGap = 8;
        int catWidth = Math.min(130, Math.max(70, (detailWidth - 24 - buttonGap) / 3));
        int exportWidth = Math.max(1, detailWidth - 24 - buttonGap - catWidth);

        categoryButton = new UiButton(
                detailX + 12,
                actionY,
                catWidth,
                23,
                Component.literal(CATEGORIES.get(categoryIndex)),
                "#",
                ButtonStyle.SECONDARY
        );

        exportButton = new UiButton(
                detailX + 12 + catWidth + buttonGap,
                actionY,
                exportWidth,
                23,
                Component.literal("Exportar al servidor"),
                "^",
                ButtonStyle.PRIMARY
        );

        closeButton = new UiButton(
                panelLeft + panelWidth - 90,
                panelTop + panelHeight - 29,
                76,
                21,
                Component.literal("Cerrar"),
                "X",
                ButtonStyle.SECONDARY
        );

        reloadFromDisk();
    }

    private void reloadFromDisk() {
        String previousId = selected != null ? selected.id : null;
        allEntries = LocalLibrary.listStructures();
        applyFilter();

        if (previousId != null) {
            selectEntry(filteredEntries.stream()
                    .filter(e -> previousId.equals(e.id))
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
            updateActionState();
        }
    }

    private void selectEntry(LocalLibrary.Entry entry) {
        selected = entry;
        selectedThumbTexture = entry != null
                ? ThumbnailGenerator.getOrCreateTexture(entry.id)
                : null;
        updateActionState();

        if (!canExport) {
            statusMessage = "No tienes permisos en este servidor para exportar.";
            statusColor = COLOR_ERROR;
        } else if (selected != null) {
            String name = selected.name == null ? selected.id : selected.name;
            statusMessage = "Lista para enviar: " + name + " (" + formatBytes(selected.fileSizeBytes) + ")";
            statusColor = COLOR_SUCCESS;
        } else if (allEntries.isEmpty()) {
            statusMessage = "No tienes estructuras guardadas en Biblioteca local.";
            statusColor = COLOR_MUTED;
        } else {
            statusMessage = "Selecciona una estructura de la lista.";
            statusColor = COLOR_MUTED;
        }
    }

    private void updateActionState() {
        if (exportButton != null) {
            exportButton.active = canExport && !exporting && selected != null;
        }
        if (categoryButton != null) {
            categoryButton.active = canExport && !exporting;
        }
    }

    private void cycleCategory() {
        categoryIndex = (categoryIndex + 1) % CATEGORIES.size();
        if (categoryButton != null) {
            categoryButton.label = Component.literal(CATEGORIES.get(categoryIndex));
        }
    }

    private void startExport() {
        if (!canExport || exporting || selected == null) {
            return;
        }

        try {
            byte[] bytes = LocalLibrary.readStructureBytes(selected.id);
            if (bytes.length > StructureCapture.MAX_COMPRESSED_BYTES) {
                statusMessage = "El archivo supera el límite máximo de 16 MB.";
                statusColor = COLOR_ERROR;
                return;
            }

            exporting = true;
            updateActionState();

            String name = selected.name == null ? selected.id : selected.name;
            statusMessage = "Enviando '" + name + "' al servidor...";
            statusColor = COLOR_ACCENT_LIGHT;

            UUID transferId = UUID.randomUUID();
            String category = CATEGORIES.get(categoryIndex);
            int chunkSize = ModNetworking.UPLOAD_CHUNK_SIZE;
            int totalChunks = Math.max(1, (int) Math.ceil((double) bytes.length / chunkSize));

            for (int i = 0; i < totalChunks; i++) {
                int start = i * chunkSize;
                int end = Math.min(bytes.length, start + chunkSize);
                byte[] slice = Arrays.copyOfRange(bytes, start, end);
                ClientPlayNetworking.send(new ExportChunkPayload(
                        transferId, i, totalChunks, category, slice
                ));
            }
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error leyendo estructura {} para exportar", selected.id, e);
            exporting = false;
            updateActionState();
            statusMessage = "No se pudo leer el archivo .dungeon local.";
            statusColor = COLOR_ERROR;
        }
    }

    public void handleExportResult(boolean success, String message) {
        exporting = false;
        updateActionState();
        statusMessage = message;
        statusColor = success ? COLOR_SUCCESS : COLOR_ERROR;
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

        if (categoryButton.contains(mouseX, mouseY)) {
            if (categoryButton.active) {
                playClickSound();
                cycleCategory();
            }
            return true;
        }

        if (exportButton.contains(mouseX, mouseY)) {
            if (exportButton.active) {
                playClickSound();
                startExport();
            }
            return true;
        }

        if (closeButton.contains(mouseX, mouseY)) {
            playClickSound();
            onClose();
            return true;
        }

        if (mouseX >= listX && mouseX <= listX + listWidth
                && mouseY >= listY && mouseY <= contentBottom) {
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
                && mouseY >= listY && mouseY <= contentBottom) {
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
        drawButton(graphics, categoryButton, mouseX, mouseY);
        drawButton(graphics, exportButton, mouseX, mouseY);
        drawButton(graphics, closeButton, mouseX, mouseY);

        int footerMaxWidth = Math.max(1, panelWidth - 125);
        String hint = font.plainSubstrByWidth("Origen: DungeonBuilder/structures/", footerMaxWidth);
        graphics.drawString(font, hint, panelLeft + 16, panelTop + panelHeight - 21, COLOR_MUTED, false);
    }

    private void renderHeader(GuiGraphics graphics) {
        graphics.drawString(font, title, panelLeft + 29, panelTop + 15, COLOR_TEXT, false);

        String count = allEntries.size() + " locales";
        graphics.drawString(font, count,
                panelLeft + panelWidth - 16 - font.width(count),
                panelTop + 15, COLOR_MUTED, false);
    }

    private void renderTopBadge(GuiGraphics graphics) {
        int maxW = Math.max(1, detailWidth - 16);
        String label = canExport
                ? "DESTINO: BIBLIOTECA DEL SERVIDOR"
                : "SOLO LECTURA • SIN PERMISOS";
        graphics.drawString(font, font.plainSubstrByWidth(label, maxW),
                detailX + 10, panelTop + 56, canExport ? COLOR_ACCENT_LIGHT : COLOR_ERROR, false);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (filteredEntries.isEmpty()) {
            String msg = allEntries.isEmpty()
                    ? "Aún no tienes estructuras guardadas localmente. Usa el Seleccionador para crear una."
                    : "Ninguna estructura coincide con tu búsqueda.";
            graphics.drawWordWrap(font, Component.literal(msg),
                    listX + 12, listY + 14,
                    Math.max(1, listWidth - 24), COLOR_MUTED);
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
                graphics.fill(rowLeft, y + 3, rowRight, y + ENTRY_HEIGHT - 3, 0xFF401A24);
                graphics.fill(rowLeft, y + 3, rowLeft + 3, y + ENTRY_HEIGHT - 3, COLOR_ACCENT);
                graphics.renderOutline(rowLeft, y + 3,
                        Math.max(1, rowRight - rowLeft), ENTRY_HEIGHT - 6, 0xFFA13B4B);
            } else if (isHovered) {
                graphics.fill(rowLeft, y + 3, rowRight, y + ENTRY_HEIGHT - 3, 0xFF2E151C);
            }

            int iconX = listX + 13;
            int iconY = y + 8;
            graphics.fill(iconX, iconY, iconX + 32, iconY + 32, isSelected ? 0xFF5C2330 : 0xFF321820);
            graphics.renderOutline(iconX, iconY, 32, 32, isSelected ? COLOR_ACCENT : COLOR_BORDER);
            graphics.drawCenteredString(font, "D", iconX + 16, iconY + 12, COLOR_ACCENT_LIGHT);

            int textX = iconX + 41;
            int availableWidth = Math.max(1, listWidth - (textX - listX) - 14);

            String name = entry.name == null ? entry.id : entry.name;
            graphics.drawString(font, font.plainSubstrByWidth(name, availableWidth),
                    textX, y + 9, isSelected ? COLOR_ACCENT_LIGHT : COLOR_TEXT, false);

            String info = entry.width + "×" + entry.height + "×" + entry.depth
                    + "  •  " + formatBytes(entry.fileSizeBytes);
            graphics.drawString(font, font.plainSubstrByWidth(info, availableWidth),
                    textX, y + 25, COLOR_MUTED, false);
        }

        graphics.disableScissor();

        int totalContentHeight = filteredEntries.size() * ENTRY_HEIGHT;
        if (totalContentHeight > listHeight && listHeight > 12) {
            int trackX = listX + listWidth - 4;
            graphics.fill(trackX, listY + 5, trackX + 2, contentBottom - 5, 0xFF361922);

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

        graphics.drawString(font, "RESUMEN DE EXPORTACIÓN", x, listY + 12, COLOR_ACCENT, false);

        if (selected == null) {
            graphics.drawCenteredString(font, "Ninguna estructura seleccionada",
                    detailX + detailWidth / 2, listY + 72, COLOR_MUTED);
            renderStatusBanner(graphics);
            return;
        }

        int contentY = listY + 34;
        int statusBannerY = actionY - 27;
        int availableForContent = Math.max(1, statusBannerY - contentY - 8);

        int thumbSize = Math.max(1, Math.min(88, Math.min(detailWidth / 3, availableForContent)));
        int thumbX = detailX + detailWidth - thumbSize - 12;
        int thumbY = contentY;

        renderThumbnail(graphics, thumbX, thumbY, thumbSize);

        int textWidth = Math.max(1, thumbX - x - 9);
        boolean compact = detailWidth < 290 || availableForContent < 86;

        String name = selected.name == null ? selected.id : selected.name;
        drawClippedLine(graphics, name, x, contentY, COLOR_ACCENT_LIGHT, textWidth);

        if (!compact && availableForContent >= 34) {
            drawClippedLine(graphics, selected.id + ".dungeon", x, contentY + 16, COLOR_MUTED, textWidth);
        }

        int dimY = contentY + (compact ? 17 : 35);
        if (availableForContent >= (compact ? 30 : 48)) {
            drawClippedLine(graphics,
                    "Dimensiones: " + selected.width + " × " + selected.height + " × " + selected.depth,
                    x, dimY, COLOR_TEXT, textWidth);
        }

        int blocksY = dimY + 16;
        if (availableForContent >= (compact ? 46 : 64)) {
            drawClippedLine(graphics,
                    "Bloques: " + selected.nonAirBlocks + " sólidos (" + selected.volume() + " tot.)",
                    x, blocksY, COLOR_TEXT, textWidth);
        }

        int sizeY = blocksY + 16;
        if (!compact && availableForContent >= 80) {
            drawClippedLine(graphics,
                    "Peso: " + formatBytes(selected.fileSizeBytes) + "  •  NBT: " + selected.blockEntityCount,
                    x, sizeY, COLOR_TEXT, textWidth);
        }

        int metaY = Math.max(contentY + (compact ? 50 : 84), thumbY + thumbSize + 8);
        int metaSpace = statusBannerY - metaY - 6;

        if (metaSpace >= 16) {
            graphics.fill(detailX + 13, metaY - 5, detailX + detailWidth - 13, metaY - 4, COLOR_BORDER);
            String author = "Autor: " + (selected.author == null ? "-" : selected.author);
            drawClippedLine(graphics, author, x, metaY, COLOR_MUTED, maxWidth);
        }

        if (metaSpace >= 32) {
            List<String> missing = findMissingMods(selected.requiredMods);
            String mods = (selected.requiredMods == null || selected.requiredMods.isEmpty())
                    ? "minecraft"
                    : String.join(", ", selected.requiredMods);
            drawClippedLine(graphics, "Mods: " + mods,
                    x, metaY + 16,
                    missing.isEmpty() ? COLOR_SUCCESS : 0xFFFF9999,
                    maxWidth);
        }

        renderStatusBanner(graphics);
    }

    private void renderStatusBanner(GuiGraphics graphics) {
        int bannerX = detailX + 12;
        int bannerY = actionY - 25;
        int bannerW = Math.max(1, detailWidth - 24);
        int bannerH = 20;

        if (bannerY <= listY + 24) {
            return;
        }

        graphics.fill(bannerX, bannerY, bannerX + bannerW, bannerY + bannerH, COLOR_PANEL_INNER);
        graphics.renderOutline(bannerX, bannerY, bannerW, bannerH, COLOR_BORDER);

        String clipped = font.plainSubstrByWidth(statusMessage, Math.max(1, bannerW - 12));
        graphics.drawString(font, clipped, bannerX + 6, bannerY + 6, statusColor, false);
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
        SECONDARY
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
            background = 0xFF211418;
            border = 0xFF422930;
            accent = 0xFF75525B;
            textColor = 0xFF85656D;
            iconBoxBg = 0x33331F25;
        } else if (button.style == ButtonStyle.PRIMARY) {
            background = hovered ? 0xFF8F2E3E : 0xFF6B222E;
            border = hovered ? COLOR_ACCENT_LIGHT : COLOR_ACCENT;
            accent = COLOR_ACCENT_LIGHT;
            textColor = COLOR_TEXT;
            iconBoxBg = 0x664A151E;
        } else {
            background = hovered ? 0xFF4A222D : 0xFF331820;
            border = hovered ? COLOR_ACCENT : COLOR_BORDER;
            accent = COLOR_ACCENT;
            textColor = COLOR_TEXT;
            iconBoxBg = 0x66401D27;
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