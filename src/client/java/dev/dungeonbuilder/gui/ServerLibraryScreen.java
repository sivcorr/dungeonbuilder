package dev.dungeonbuilder.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dungeonbuilder.network.ModNetworking.DeleteServerStructurePayload;
import dev.dungeonbuilder.network.ModNetworking.PlaceServerStructurePayload;
import dev.dungeonbuilder.network.ModNetworking.RequestPermissionsDataPayload;
import dev.dungeonbuilder.network.ModNetworking.RequestServerChestConfigPayload;
import dev.dungeonbuilder.network.ModNetworking.RequestServerLibraryPayload;
import dev.dungeonbuilder.network.ModNetworking.RequestServerStructurePayload;
import dev.dungeonbuilder.network.ModNetworking.RequestUndoPayload;
import dev.dungeonbuilder.network.ModNetworking.ServerEntryInfo;
import dev.dungeonbuilder.placement.ClientUndoState;
import dev.dungeonbuilder.preview.PreviewManager;
import dev.dungeonbuilder.storage.ThumbnailGenerator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Biblioteca compartida del servidor ("Biblioteca del servidor"). */
public class ServerLibraryScreen extends Screen {

    private static final int ENTRY_HEIGHT = 52;
    private static final long AUTO_THUMB_MAX_BYTES = 2 * 1024 * 1024L;

    private static final int COLOR_BACKGROUND = 0xF00B141D;
    private static final int COLOR_HEADER = 0xFF122638;
    private static final int COLOR_PANEL = 0xF0101D2A;
    private static final int COLOR_PANEL_INNER = 0xFF0C1621;

    private static final int COLOR_ACCENT = 0xFF38BDF8;
    private static final int COLOR_ACCENT_LIGHT = 0xFFBAE6FD;
    private static final int COLOR_ACCENT_DARK = 0xFF1F638C;

    private static final int COLOR_TEXT = 0xFFF0F8FF;
    private static final int COLOR_MUTED = 0xFF91AFC4;
    private static final int COLOR_BORDER = 0xFF274D6B;
    private static final int COLOR_SUCCESS = 0xFF91D5A6;
    private static final int COLOR_ERROR = 0xFFFF8585;

    private static final List<String> CATEGORIES = List.of(
            "Todas", "General", "Dungeons", "Jefes", "Eventos", "Ruinas", "Decoración"
    );

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.systemDefault());

    private boolean canPreview;
    private boolean canPlace;
    private boolean canDelete;
    private boolean canEditServerLoot;
    private String roleName;
    private List<ServerEntryInfo> allEntries;
    private List<ServerEntryInfo> filteredEntries = List.of();

    private ServerEntryInfo selected;
    private ResourceLocation selectedThumbTexture;
    private final Set<String> requestedThumbs = new HashSet<>();

    private int categoryIndex = 0;
    private double scrollOffset;
    private long confirmPlaceUntilMs;
    private long confirmDeleteUntilMs;
    private long confirmUndoUntilMs;

    private EditBox searchBox;
    private String statusMessage = "";
    private int statusColor = COLOR_MUTED;

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

    private UiButton syncButton;
    private UiButton categoryButton;
    private UiButton previewButton;
    private UiButton placeButton;
    private UiButton lootButton;
    private UiButton deleteButton;
    private UiButton undoButton;
    private UiButton permissionsButton;
    private UiButton closeButton;

    public ServerLibraryScreen() {
        this(false, false, false, false, "PLAYER", List.of());
    }

    public ServerLibraryScreen(boolean canPreview, boolean canPlace, boolean canDelete,
                               boolean canEditServerLoot, String roleName, List<ServerEntryInfo> entries) {
        super(Component.literal("Biblioteca del servidor"));
        this.canPreview = canPreview;
        this.canPlace = canPlace;
        this.canDelete = canDelete;
        this.canEditServerLoot = canEditServerLoot;
        this.roleName = (roleName == null || roleName.isBlank()) ? "PLAYER" : roleName;
        this.allEntries = entries == null ? List.of() : new ArrayList<>(entries);
    }

    private boolean isAdminRole() {
        return "ADMIN".equalsIgnoreCase(roleName);
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

        int syncWidth = Math.min(116, Math.max(76, listWidth / 3 + 12));
        int searchWidth = Math.max(1, listWidth - syncWidth - 8);

        searchBox = new EditBox(
                font,
                listX,
                panelTop + 48,
                searchWidth,
                23,
                Component.literal("Buscar")
        );
        searchBox.setMaxLength(48);
        searchBox.setHint(Component.literal("Buscar estructuras..."));
        searchBox.setValue(previousQuery);
        searchBox.setResponder(query -> applyFilter());
        addRenderableWidget(searchBox);

        syncButton = new UiButton(
                listX + listWidth - syncWidth,
                panelTop + 47,
                syncWidth,
                25,
                Component.literal("Sincronizar"),
                "R",
                ButtonStyle.SECONDARY
        );

        int catBtnWidth = Math.min(132, Math.max(72, detailWidth / 3));
        categoryButton = new UiButton(
                detailX + detailWidth - catBtnWidth,
                panelTop + 47,
                catBtnWidth,
                25,
                Component.literal(CATEGORIES.get(categoryIndex)),
                "#",
                ButtonStyle.SECONDARY
        );

        int buttonGap = 8;
        int actionWidth = Math.max(1, (detailWidth - 20 - buttonGap) / 2);

        previewButton = new UiButton(
                detailX + 10,
                actionY,
                actionWidth,
                22,
                Component.literal("Previsualizar"),
                ">",
                ButtonStyle.PRIMARY
        );

        placeButton = new UiButton(
                detailX + 10 + actionWidth + buttonGap,
                actionY,
                actionWidth,
                22,
                Component.literal("Colocar aquí"),
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
                Component.literal("Eliminar del servidor"),
                "X",
                ButtonStyle.DANGER
        );

        int footerY = panelTop + panelHeight - 28;
        closeButton = new UiButton(
                panelLeft + panelWidth - 88,
                footerY,
                76,
                21,
                Component.literal("Cerrar"),
                "X",
                ButtonStyle.SECONDARY
        );

        int nextRight = closeButton.x - 6;
        if (isAdminRole()) {
            permissionsButton = new UiButton(
                    nextRight - 96,
                    footerY,
                    96,
                    21,
                    Component.literal("Permisos"),
                    "*",
                    ButtonStyle.PRIMARY
            );
            nextRight = permissionsButton.x - 6;
        } else {
            permissionsButton = null;
        }

        undoButton = new UiButton(
                nextRight - 100,
                footerY,
                100,
                21,
                Component.literal("Deshacer"),
                "<",
                ButtonStyle.DANGER
        );
        refreshUndoButton();

        applyFilter();
        selectEntry(filteredEntries.isEmpty() ? null : filteredEntries.get(0));
    }

    public void refreshUndoButton() {
        confirmUndoUntilMs = 0;
        if (undoButton != null) {
            undoButton.active = ClientUndoState.isAvailable();
            undoButton.label = Component.literal("Deshacer");
        }
    }

    public void updateFromServer(boolean canPreview, boolean canPlace, boolean canDelete,
                                 boolean canEditServerLoot, String roleName, List<ServerEntryInfo> entries) {
        String previousId = selected != null ? selected.id() : null;
        this.canPreview = canPreview;
        this.canPlace = canPlace;
        this.canDelete = canDelete;
        this.canEditServerLoot = canEditServerLoot;
        this.roleName = (roleName == null || roleName.isBlank()) ? "PLAYER" : roleName;
        this.allEntries = entries == null ? List.of() : new ArrayList<>(entries);
        this.requestedThumbs.clear();

        applyFilter();

        if (previousId != null) {
            selectEntry(filteredEntries.stream()
                    .filter(e -> previousId.equals(e.id()))
                    .findFirst()
                    .orElse(filteredEntries.isEmpty() ? null : filteredEntries.get(0)));
        } else {
            selectEntry(filteredEntries.isEmpty() ? null : filteredEntries.get(0));
        }

        statusMessage = "Sincronizado (" + this.roleName + "): " + allEntries.size() + " estructuras en el servidor.";
        statusColor = COLOR_SUCCESS;
    }

    public void onThumbnailReady(String structureId) {
        if (selected != null && selected.id().equals(structureId)) {
            selectedThumbTexture = ThumbnailGenerator.getCachedServerTexture(structureId);
            if (statusMessage.startsWith("Cargando miniatura")) {
                statusMessage = "";
            }
        }
    }

    public void showStatus(boolean success, String message) {
        statusMessage = message;
        statusColor = success ? COLOR_SUCCESS : COLOR_ERROR;
    }

    private void applyFilter() {
        String query = searchBox != null
                ? searchBox.getValue().trim().toLowerCase(Locale.ROOT)
                : "";
        String activeCategory = CATEGORIES.get(categoryIndex);
        boolean filterByCategory = !"Todas".equals(activeCategory);

        List<ServerEntryInfo> matches = new ArrayList<>();
        for (ServerEntryInfo entry : allEntries) {
            if (filterByCategory && !activeCategory.equalsIgnoreCase(entry.category())) {
                continue;
            }
            if (!query.isEmpty()) {
                String name = entry.name().toLowerCase(Locale.ROOT);
                String id = entry.id().toLowerCase(Locale.ROOT);
                String author = entry.author().toLowerCase(Locale.ROOT);
                if (!name.contains(query) && !id.contains(query) && !author.contains(query)) {
                    continue;
                }
            }
            matches.add(entry);
        }
        filteredEntries = matches;
        clampScroll();

        if (selected != null) {
            ServerEntryInfo match = filteredEntries.stream()
                    .filter(e -> e.id().equals(selected.id()))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                selectEntry(filteredEntries.isEmpty() ? null : filteredEntries.get(0));
            } else {
                selected = match;
                updateActionButtons();
            }
        } else {
            updateActionButtons();
        }
    }

    private void selectEntry(ServerEntryInfo entry) {
        selected = entry;
        resetConfirmations();
        updateActionButtons();

        if (entry == null) {
            selectedThumbTexture = null;
            return;
        }

        selectedThumbTexture = ThumbnailGenerator.getCachedServerTexture(entry.id());
        if (selectedThumbTexture == null) {
            byte[] cachedBytes = PreviewManager.getCachedServerBytes(entry.id());
            if (cachedBytes != null && minecraft != null && minecraft.level != null) {
                selectedThumbTexture = ThumbnailGenerator.getOrCreateServerTexture(
                        entry.id(), cachedBytes, minecraft.level.holderLookup(Registries.BLOCK));
            } else if (entry.fileSizeBytes() <= AUTO_THUMB_MAX_BYTES && !requestedThumbs.contains(entry.id())) {
                requestedThumbs.add(entry.id());
                statusMessage = "Cargando miniatura...";
                statusColor = COLOR_ACCENT_LIGHT;
                ClientPlayNetworking.send(new RequestServerStructurePayload(entry.id(), false));
            }
        }
    }

    private void cycleCategory() {
        categoryIndex = (categoryIndex + 1) % CATEGORIES.size();
        if (categoryButton != null) {
            categoryButton.label = Component.literal(CATEGORIES.get(categoryIndex));
        }
        applyFilter();
    }

    private void handlePreviewClick() {
        if (selected == null || !canPreview) {
            return;
        }
        byte[] cachedBytes = PreviewManager.getCachedServerBytes(selected.id());
        if (cachedBytes != null) {
            String id = selected.id();
            onClose();
            PreviewManager.startServerPreview(id, cachedBytes);
            return;
        }

        statusMessage = "Descargando holograma del servidor...";
        statusColor = COLOR_ACCENT_LIGHT;
        ClientPlayNetworking.send(new RequestServerStructurePayload(selected.id(), true));
    }

    private void handlePlaceClick() {
        if (selected == null || !canPlace || minecraft == null || minecraft.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now > confirmPlaceUntilMs) {
            confirmPlaceUntilMs = now + 3500L;
            placeButton.label = Component.literal("¿Colocar aquí?");
            return;
        }

        String id = selected.id();
        var pos = minecraft.player.blockPosition();
        onClose();
        PreviewManager.clear();
        ClientPlayNetworking.send(new PlaceServerStructurePayload(id, pos, 0));
    }

    private void handleLootClick() {
        if (selected == null || !canEditServerLoot) {
            return;
        }
        statusMessage = "Cargando cofres del servidor...";
        statusColor = COLOR_ACCENT_LIGHT;
        ClientPlayNetworking.send(new RequestServerChestConfigPayload(selected.id()));
    }

    private void handleDeleteClick() {
        if (selected == null || !canDelete) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now > confirmDeleteUntilMs) {
            confirmDeleteUntilMs = now + 3500L;
            deleteButton.label = Component.literal("¿Confirmar eliminación?");
            return;
        }

        String id = selected.id();
        PreviewManager.removeCachedServerBytes(id);
        ThumbnailGenerator.invalidateServer(id);
        requestedThumbs.remove(id);
        statusMessage = "Eliminando estructura del servidor...";
        statusColor = COLOR_ACCENT_LIGHT;
        ClientPlayNetworking.send(new DeleteServerStructurePayload(id));
    }

    private void handleUndoClick() {
        if (!ClientUndoState.isAvailable()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now > confirmUndoUntilMs) {
            confirmUndoUntilMs = now + 3500L;
            undoButton.label = Component.literal("¿Deshacer?");
            statusMessage = "Pulsa de nuevo para deshacer: " + ClientUndoState.structureName();
            statusColor = COLOR_ACCENT_LIGHT;
            return;
        }

        onClose();
        ClientPlayNetworking.send(new RequestUndoPayload());
    }

    private void resetConfirmations() {
        confirmPlaceUntilMs = 0;
        confirmDeleteUntilMs = 0;
        confirmUndoUntilMs = 0;
        if (placeButton != null) {
            placeButton.label = Component.literal("Colocar aquí");
        }
        if (deleteButton != null) {
            deleteButton.label = Component.literal("Eliminar del servidor");
        }
        if (undoButton != null) {
            undoButton.label = Component.literal("Deshacer");
        }
    }

    private void updateActionButtons() {
        boolean hasSelection = selected != null;
        if (previewButton != null) previewButton.active = hasSelection && canPreview;
        if (placeButton != null) placeButton.active = hasSelection && canPlace;
        if (lootButton != null) lootButton.active = hasSelection && canEditServerLoot && selected.blockEntityCount() > 0;
        if (deleteButton != null) deleteButton.active = hasSelection && canDelete;
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        if (confirmPlaceUntilMs > 0 && now > confirmPlaceUntilMs) {
            confirmPlaceUntilMs = 0;
            if (placeButton != null) {
                placeButton.label = Component.literal("Colocar aquí");
            }
        }
        if (confirmDeleteUntilMs > 0 && now > confirmDeleteUntilMs) {
            confirmDeleteUntilMs = 0;
            if (deleteButton != null) {
                deleteButton.label = Component.literal("Eliminar del servidor");
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

        if (syncButton.contains(mouseX, mouseY)) {
            playClickSound();
            statusMessage = "Sincronizando con el servidor...";
            statusColor = COLOR_ACCENT_LIGHT;
            ClientPlayNetworking.send(new RequestServerLibraryPayload());
            return true;
        }

        if (categoryButton.contains(mouseX, mouseY)) {
            playClickSound();
            cycleCategory();
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

        if (isAdminRole() && permissionsButton != null && permissionsButton.contains(mouseX, mouseY)) {
            playClickSound();
            statusMessage = "Abriendo configuración de permisos...";
            statusColor = COLOR_ACCENT_LIGHT;
            ClientPlayNetworking.send(new RequestPermissionsDataPayload());
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

        int serverBadgeW = Math.max(1, detailWidth - (categoryButton != null ? categoryButton.width + 8 : 0));
        graphics.fill(detailX, panelTop + 47, detailX + serverBadgeW, panelTop + 72, COLOR_PANEL_INNER);
        graphics.renderOutline(detailX, panelTop + 47, serverBadgeW, 25, COLOR_BORDER);

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

        drawButton(graphics, syncButton, mouseX, mouseY);
        drawButton(graphics, categoryButton, mouseX, mouseY);
        drawButton(graphics, previewButton, mouseX, mouseY);
        drawButton(graphics, placeButton, mouseX, mouseY);
        drawButton(graphics, lootButton, mouseX, mouseY);
        drawButton(graphics, deleteButton, mouseX, mouseY);

        int leftMostRightButton = closeButton.x;
        if (ClientUndoState.isAvailable() && undoButton != null) {
            drawButton(graphics, undoButton, mouseX, mouseY);
            leftMostRightButton = Math.min(leftMostRightButton, undoButton.x);
        }
        if (isAdminRole() && permissionsButton != null) {
            drawButton(graphics, permissionsButton, mouseX, mouseY);
            leftMostRightButton = Math.min(leftMostRightButton, permissionsButton.x);
        }
        drawButton(graphics, closeButton, mouseX, mouseY);

        int footerMaxWidth = Math.max(1, leftMostRightButton - (panelLeft + 16) - 10);
        String hintText = ClientUndoState.isAvailable()
                ? "Última colocación: " + ClientUndoState.structureName()
                : "Usa el Exportador para subir tus construcciones a esta biblioteca.";
        String hint = font.plainSubstrByWidth(hintText, footerMaxWidth);
        graphics.drawString(font, hint, panelLeft + 16, panelTop + panelHeight - 20, COLOR_MUTED, false);
    }

    private void renderHeader(GuiGraphics graphics) {
        graphics.drawString(font, title, panelLeft + 29, panelTop + 14, COLOR_TEXT, false);

        String count = allEntries.size() + " disponibles";
        graphics.drawString(font, count,
                panelLeft + panelWidth - 16 - font.width(count),
                panelTop + 14, COLOR_MUTED, false);
    }

    private void renderTopBadge(GuiGraphics graphics) {
        int serverBadgeW = Math.max(1, detailWidth - (categoryButton != null ? categoryButton.width + 8 : 0));
        int maxW = Math.max(1, serverBadgeW - 16);
        String serverLabel = getServerBadgeLabel();
        graphics.drawString(font, font.plainSubstrByWidth(serverLabel, maxW),
                detailX + 8, panelTop + 56, COLOR_ACCENT_LIGHT, false);
    }

    private String getServerBadgeLabel() {
        if (minecraft != null && minecraft.getCurrentServer() != null) {
            return "SERVIDOR: " + minecraft.getCurrentServer().name + " • ROL: " + roleName;
        }
        return "SERVIDOR: MUNDO ACTUAL • ROL: " + roleName;
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (filteredEntries.isEmpty()) {
            if (allEntries.isEmpty()) {
                int boxHeight = Math.max(1, contentBottom - listY);
                int centerX = listX + listWidth / 2;
                int centerY = listY + boxHeight / 2;
                int iconX = centerX - 18;
                int iconY = Math.max(listY + 14, centerY - 52);

                graphics.fill(iconX, iconY, iconX + 36, iconY + 36, 0xFF162E42);
                graphics.renderOutline(iconX, iconY, 36, 36, COLOR_ACCENT);
                graphics.drawCenteredString(font, "S", centerX, iconY + 14, COLOR_ACCENT_LIGHT);

                if (boxHeight >= 115) {
                    graphics.drawCenteredString(font,
                            font.plainSubstrByWidth("BIBLIOTECA COMPARTIDA", Math.max(1, listWidth - 20)),
                            centerX, iconY + 46, COLOR_ACCENT_LIGHT);
                }
                if (boxHeight >= 145) {
                    graphics.drawWordWrap(font,
                            Component.literal("Aún no hay estructuras en este servidor. Usa el Exportador para subir una."),
                            listX + 14, iconY + 64,
                            Math.max(1, listWidth - 28), COLOR_MUTED);
                }
            } else {
                graphics.drawWordWrap(font,
                        Component.literal("Ninguna estructura coincide con tu búsqueda o categoría."),
                        listX + 10, listY + 12,
                        Math.max(1, listWidth - 20), COLOR_MUTED);
            }
            return;
        }

        graphics.enableScissor(listX + 1, listY + 1, listX + listWidth - 1, contentBottom - 1);

        for (int index = 0; index < filteredEntries.size(); index++) {
            int y = (int) (listY + index * ENTRY_HEIGHT - scrollOffset);
            if (y + ENTRY_HEIGHT < listY || y > contentBottom) {
                continue;
            }

            ServerEntryInfo entry = filteredEntries.get(index);
            boolean isSelected = selected != null && selected.id().equals(entry.id());
            boolean isHovered = mouseX >= listX && mouseX <= listX + listWidth
                    && mouseY >= Math.max(y, listY)
                    && mouseY <= Math.min(y + ENTRY_HEIGHT, contentBottom);

            int rowLeft = listX + 5;
            int rowRight = listX + listWidth - 7;

            if (isSelected) {
                graphics.fill(rowLeft, y + 3, rowRight, y + ENTRY_HEIGHT - 3, 0xFF17364F);
                graphics.fill(rowLeft, y + 3, rowLeft + 3, y + ENTRY_HEIGHT - 3, COLOR_ACCENT);
                graphics.renderOutline(rowLeft, y + 3,
                        Math.max(1, rowRight - rowLeft), ENTRY_HEIGHT - 6, 0xFF2D76A8);
            } else if (isHovered) {
                graphics.fill(rowLeft, y + 3, rowRight, y + ENTRY_HEIGHT - 3, 0xFF14283B);
            }

            int iconX = listX + 13;
            int iconY = y + 9;
            graphics.fill(iconX, iconY, iconX + 34, iconY + 34, isSelected ? 0xFF1E4C70 : 0xFF15293B);
            graphics.renderOutline(iconX, iconY, 34, 34, isSelected ? COLOR_ACCENT : COLOR_BORDER);
            graphics.drawCenteredString(font, "S", iconX + 17, iconY + 13, COLOR_ACCENT_LIGHT);

            int textX = iconX + 44;
            int availableWidth = Math.max(1, listWidth - (textX - listX) - 15);

            graphics.drawString(font,
                    font.plainSubstrByWidth(entry.name(), availableWidth),
                    textX, y + 9,
                    isSelected ? COLOR_ACCENT_LIGHT : COLOR_TEXT,
                    false);

            String sub = entry.width() + "×" + entry.height() + "×" + entry.depth()
                    + " • " + entry.category();
            graphics.drawString(font,
                    font.plainSubstrByWidth(sub, availableWidth),
                    textX, y + 26, COLOR_MUTED, false);
        }

        graphics.disableScissor();

        int totalContentHeight = filteredEntries.size() * ENTRY_HEIGHT;
        if (totalContentHeight > listHeight && listHeight > 12) {
            int trackX = listX + listWidth - 4;
            graphics.fill(trackX, listY + 5, trackX + 2, contentBottom - 5, 0xFF183045);

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

        graphics.drawString(font, "DETALLES DE LA ESTRUCTURA", x, listY + 12, COLOR_ACCENT, false);

        if (selected == null) {
            graphics.drawCenteredString(font, "Selecciona una estructura de la lista.",
                    detailX + detailWidth / 2, listY + 72, COLOR_MUTED);
            return;
        }

        int contentY = listY + 34;
        boolean hasStatus = !statusMessage.isEmpty();
        int infoBottomLimit = hasStatus ? (actionY - 27) : (actionY - 7);
        int availableForContent = Math.max(1, infoBottomLimit - contentY);

        int thumbSize = Math.max(1, Math.min(92, Math.min(detailWidth / 3, availableForContent)));
        int thumbX = detailX + detailWidth - thumbSize - 12;
        int thumbY = contentY;

        renderThumbnail(graphics, thumbX, thumbY, thumbSize);

        int textWidth = Math.max(1, thumbX - x - 9);
        boolean compact = detailWidth < 300 || availableForContent < 88;

        drawClippedLine(graphics, selected.name(), x, contentY, COLOR_ACCENT_LIGHT, textWidth);

        if (!compact && availableForContent >= 35) {
            drawClippedLine(graphics,
                    selected.id() + ".dungeon  •  " + selected.category(),
                    x, contentY + 16, COLOR_MUTED, textWidth);
        }

        int dimY = contentY + (compact ? 17 : 35);
        if (availableForContent >= (compact ? 30 : 48)) {
            drawClippedLine(graphics,
                    "Dimensiones: " + selected.width() + " × " + selected.height() + " × " + selected.depth(),
                    x, dimY, COLOR_TEXT, textWidth);
        }

        int blocksY = dimY + 16;
        if (availableForContent >= (compact ? 46 : 64)) {
            drawClippedLine(graphics,
                    "Bloques: " + selected.nonAirBlocks() + " sólidos (" + selected.volume() + " tot.)",
                    x, blocksY, COLOR_TEXT, textWidth);
        }

        int nbtY = blocksY + 16;
        if (!compact && availableForContent >= 80) {
            drawClippedLine(graphics,
                    "Peso: " + formatBytes(selected.fileSizeBytes()) + "  •  NBT: " + selected.blockEntityCount(),
                    x, nbtY, COLOR_TEXT, textWidth);
        }

        int metadataY = Math.max(contentY + (compact ? 50 : 84), thumbY + thumbSize + 7);
        int metaAvailable = infoBottomLimit - metadataY - 4;

        if (metaAvailable >= 13) {
            graphics.fill(detailX + 13, metadataY - 5, detailX + detailWidth - 13, metadataY - 4, COLOR_BORDER);
            drawClippedLine(graphics, "Subido por: " + selected.author(), x, metadataY, COLOR_MUTED, maxWidth);
        }

        if (metaAvailable >= 29) {
            drawClippedLine(graphics,
                    "Fecha: " + DATE_FORMAT.format(Instant.ofEpochMilli(selected.uploadedAt())),
                    x, metadataY + 15, COLOR_MUTED, maxWidth);
        }

        if (metaAvailable >= 45) {
            List<String> missingMods = findMissingMods(selected.requiredMods());
            String mods = selected.requiredMods() == null || selected.requiredMods().isEmpty()
                    ? "minecraft"
                    : String.join(", ", selected.requiredMods());
            drawClippedLine(graphics, "Mods: " + mods,
                    x, metadataY + 30,
                    missingMods.isEmpty() ? COLOR_SUCCESS : 0xFFFF9999,
                    maxWidth);

            if (!missingMods.isEmpty() && metaAvailable >= 61) {
                drawClippedLine(graphics, "Faltan en tu cliente: " + String.join(", ", missingMods),
                        x, metadataY + 45, 0xFFFF9999, maxWidth);
            }
        }

        if (hasStatus && actionY - 25 > listY + 24) {
            int bannerX = detailX + 10;
            int bannerY = actionY - 24;
            int bannerW = Math.max(1, detailWidth - 20);
            graphics.fill(bannerX, bannerY, bannerX + bannerW, bannerY + 20, COLOR_PANEL_INNER);
            graphics.renderOutline(bannerX, bannerY, bannerW, 20, COLOR_BORDER);

            String clipped = font.plainSubstrByWidth(statusMessage, Math.max(1, bannerW - 12));
            graphics.drawString(font, clipped, bannerX + 6, bannerY + 6, statusColor, false);
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
            String placeholder = (selected != null && selected.fileSizeBytes() <= AUTO_THUMB_MAX_BYTES) ? "..." : "S";
            graphics.drawCenteredString(font, placeholder, x + size / 2, y + size / 2 - 4, COLOR_ACCENT_LIGHT);
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
            background = 0xFF141D26;
            border = 0xFF283A4A;
            accent = 0xFF526D82;
            textColor = 0xFF667F94;
            iconBoxBg = 0x3322313F;
        } else if (button.style == ButtonStyle.PRIMARY) {
            background = hovered ? 0xFF216A99 : 0xFF184D70;
            border = hovered ? COLOR_ACCENT_LIGHT : COLOR_ACCENT;
            accent = COLOR_ACCENT_LIGHT;
            textColor = COLOR_TEXT;
            iconBoxBg = 0x66123B57;
        } else if (button.style == ButtonStyle.DANGER) {
            background = hovered ? 0xFF6A2E3B : 0xFF42232B;
            border = hovered ? 0xFFFF9FB0 : 0xFF9B5565;
            accent = 0xFFFF9FB0;
            textColor = COLOR_TEXT;
            iconBoxBg = 0x664A1E28;
        } else {
            background = hovered ? 0xFF203F59 : 0xFF162C3F;
            border = hovered ? COLOR_ACCENT : COLOR_BORDER;
            accent = COLOR_ACCENT;
            textColor = COLOR_TEXT;
            iconBoxBg = 0x661D3952;
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

        int availableTextWidth = Math.max(1, button.width - 28);
        String text = font.plainSubstrByWidth(button.label.getString(), availableTextWidth);
        graphics.drawCenteredString(font, text,
                button.x + button.width / 2 + (button.width >= 25 ? 9 : 0),
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