package dev.dungeonbuilder.gui;

import dev.dungeonbuilder.network.ModNetworking.RequestServerLibraryPayload;
import dev.dungeonbuilder.network.ModNetworking.SavePermissionsConfigPayload;
import dev.dungeonbuilder.permissions.PermissionsManager.PermissionsSnapshot;
import dev.dungeonbuilder.permissions.PermissionsManager.PlayerAssignment;
import dev.dungeonbuilder.permissions.PermissionsManager.PlayerRoleInfo;
import dev.dungeonbuilder.permissions.PermissionsManager.RolePerms;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pantalla gráfica de administración de roles y permisos dentro de Minecraft. */
public class PermissionsScreen extends Screen {

    private static final int PLAYER_ROW_HEIGHT = 28;
    private static final int TOGGLE_HEIGHT = 26;
    private static final int TOGGLE_GAP = 4;

    private static final int COLOR_BACKGROUND = 0xF00B141D;
    private static final int COLOR_HEADER = 0xFF122638;
    private static final int COLOR_PANEL = 0xF0101D2A;
    private static final int COLOR_PANEL_INNER = 0xFF0C1621;

    private static final int COLOR_ACCENT = 0xFF38BDF8;
    private static final int COLOR_ACCENT_LIGHT = 0xFFBAE6FD;
    private static final int COLOR_ACCENT_DARK = 0xFF1F638C;
    private static final int COLOR_GOLD = 0xFFFACC15;

    private static final int COLOR_TEXT = 0xFFF0F8FF;
    private static final int COLOR_MUTED = 0xFF91AFC4;
    private static final int COLOR_BORDER = 0xFF274D6B;
    private static final int COLOR_SUCCESS = 0xFF4ADE80;
    private static final int COLOR_SUCCESS_BG = 0xFF143A26;
    private static final int COLOR_ERROR = 0xFFF87171;
    private static final int COLOR_ERROR_BG = 0xFF3F161D;

    private static final String[] PERM_LABELS = {
            "Ver biblioteca del servidor",
            "Previsualizar hologramas",
            "Colocar del servidor",
            "Exportar al servidor",
            "Eliminar del servidor",
            "Colocar locales en servidor",
            "Editar cofres del servidor"
    };

    private static final class EditablePlayer {
        final String name;
        String assignedRole;
        String effectiveRole;
        final boolean online;
        final boolean forcedOpAdmin;

        EditablePlayer(String name, String assignedRole, String effectiveRole, boolean online, boolean forcedOpAdmin) {
            this.name = name;
            this.assignedRole = assignedRole == null ? "" : assignedRole;
            this.effectiveRole = effectiveRole == null || effectiveRole.isBlank() ? "PLAYER" : effectiveRole;
            this.online = online;
            this.forcedOpAdmin = forcedOpAdmin;
        }
    }

    private final Screen parent;
    private String defaultRole;
    private String editingRole = "PLAYER";

    private boolean[] playerFlags = new boolean[PERM_LABELS.length];
    private boolean[] builderFlags = new boolean[PERM_LABELS.length];
    private final List<EditablePlayer> players = new ArrayList<>();

    private boolean saving = false;
    private boolean dirty = false;
    private String statusMessage = "Edita los permisos por rol o asigna roles y pulsa Guardar.";
    private int statusColor = COLOR_MUTED;

    private EditBox addPlayerBox;
    private double playerScrollOffset;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    private int leftX;
    private int leftWidth;
    private int rightX;
    private int rightWidth;
    private int contentY;
    private int contentBottom;

    private int toggleStartY;
    private int playerListY;
    private int playerListHeight;

    private UiButton tabPlayerButton;
    private UiButton tabBuilderButton;
    private UiButton defaultRoleButton;
    private UiButton addPlayerButton;
    private UiButton backButton;
    private UiButton saveButton;

    public PermissionsScreen(Screen parent, PermissionsSnapshot snapshot) {
        super(Component.literal("Permisos del servidor"));
        this.parent = parent;
        applySnapshot(snapshot);
    }

    public void applySnapshot(PermissionsSnapshot snapshot) {
        this.defaultRole = "BUILDER".equalsIgnoreCase(snapshot.defaultRole()) ? "BUILDER" : "PLAYER";
        this.playerFlags = toArray(snapshot.playerPerms());
        this.builderFlags = toArray(snapshot.builderPerms());
        this.players.clear();
        for (PlayerRoleInfo p : snapshot.players()) {
            this.players.add(new EditablePlayer(
                    p.name(), p.assignedRole(), p.effectiveRole(), p.online(), p.forcedOpAdmin()
            ));
        }
        this.dirty = false;
        updateTabLabels();
    }

    private static boolean[] toArray(RolePerms rp) {
        return new boolean[]{
                rp.view(), rp.preview(), rp.place(), rp.export(), rp.delete(), rp.placeLocal(), rp.editServerLoot()
        };
    }

    private static RolePerms fromArray(boolean[] f) {
        return new RolePerms(f[0], f[1], f[2], f[3], f[4], f[5], f[6]);
    }

    @Override
    protected void init() {
        panelWidth = Math.max(1, Math.min(860, width - 12));
        panelHeight = Math.max(1, Math.min(430, height - 12));

        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int innerWidth = Math.max(1, panelWidth - 32);
        leftX = panelLeft + 16;
        leftWidth = Math.max(1, (int) (innerWidth * 0.48));
        rightX = leftX + leftWidth + 12;
        rightWidth = Math.max(1, panelLeft + panelWidth - 16 - rightX);

        contentY = panelTop + 78;
        contentBottom = panelTop + panelHeight - 40;
        toggleStartY = contentY + 26;
        playerListY = contentY + 56;
        playerListHeight = Math.max(1, contentBottom - playerListY - 6);

        int tabGap = 6;
        int tabW = Math.min(118, Math.max(70, (leftWidth - tabGap) / 2));
        tabPlayerButton = new UiButton(
                leftX,
                panelTop + 47,
                tabW,
                25,
                Component.literal("Rol: PLAYER"),
                "1",
                ButtonStyle.PRIMARY
        );
        tabBuilderButton = new UiButton(
                leftX + tabW + tabGap,
                panelTop + 47,
                tabW,
                25,
                Component.literal("Rol: BUILDER"),
                "2",
                ButtonStyle.SECONDARY
        );

        defaultRoleButton = new UiButton(
                rightX,
                panelTop + 47,
                Math.min(230, rightWidth),
                25,
                Component.literal("Rol por defecto: " + defaultRole),
                "*",
                ButtonStyle.SECONDARY
        );

        String previousInput = addPlayerBox != null ? addPlayerBox.getValue() : "";
        int addBtnW = Math.min(92, Math.max(66, rightWidth / 3));
        int boxW = Math.max(1, rightWidth - 20 - addBtnW - 6);

        addPlayerBox = new EditBox(
                font,
                rightX + 10,
                contentY + 27,
                boxW,
                22,
                Component.literal("Nombre de jugador")
        );
        addPlayerBox.setMaxLength(32);
        addPlayerBox.setHint(Component.literal("Añadir jugador..."));
        addPlayerBox.setValue(previousInput);
        addRenderableWidget(addPlayerBox);

        addPlayerButton = new UiButton(
                rightX + 10 + boxW + 6,
                contentY + 26,
                addBtnW,
                24,
                Component.literal("Añadir"),
                "+",
                ButtonStyle.SECONDARY
        );

        backButton = new UiButton(
                panelLeft + panelWidth - 224,
                panelTop + panelHeight - 31,
                84,
                22,
                Component.literal("Volver"),
                "<",
                ButtonStyle.SECONDARY
        );

        saveButton = new UiButton(
                panelLeft + panelWidth - 132,
                panelTop + panelHeight - 31,
                116,
                22,
                Component.literal("Guardar"),
                "S",
                ButtonStyle.PRIMARY
        );

        updateTabLabels();
        clampPlayerScroll();
    }

    private void updateTabLabels() {
        if (tabPlayerButton != null) {
            tabPlayerButton.style = "PLAYER".equals(editingRole) ? ButtonStyle.PRIMARY : ButtonStyle.SECONDARY;
        }
        if (tabBuilderButton != null) {
            tabBuilderButton.style = "BUILDER".equals(editingRole) ? ButtonStyle.PRIMARY : ButtonStyle.SECONDARY;
        }
        if (defaultRoleButton != null) {
            defaultRoleButton.label = Component.literal("Defecto: " + defaultRole);
        }
        if (saveButton != null) {
            saveButton.active = !saving;
        }
    }

    private boolean[] currentFlags() {
        return "BUILDER".equals(editingRole) ? builderFlags : playerFlags;
    }

    private void cycleDefaultRole() {
        defaultRole = "PLAYER".equals(defaultRole) ? "BUILDER" : "PLAYER";
        for (EditablePlayer p : players) {
            if (!p.forcedOpAdmin && p.assignedRole.isEmpty()) {
                p.effectiveRole = defaultRole;
            }
        }
        dirty = true;
        updateTabLabels();
        statusMessage = "Rol por defecto cambiado a " + defaultRole + " (recuerda guardar).";
        statusColor = COLOR_ACCENT_LIGHT;
    }

    private void addOrPromotePlayer() {
        if (addPlayerBox == null) {
            return;
        }
        String raw = addPlayerBox.getValue().trim();
        if (raw.isEmpty() || raw.contains(" ")) {
            statusMessage = "Escribe un nombre de jugador válido (sin espacios).";
            statusColor = COLOR_ERROR;
            return;
        }

        for (EditablePlayer p : players) {
            if (p.name.equalsIgnoreCase(raw)) {
                if (!p.forcedOpAdmin && p.assignedRole.isEmpty()) {
                    p.assignedRole = "BUILDER";
                    p.effectiveRole = "BUILDER";
                    dirty = true;
                    statusMessage = p.name + " asignado a BUILDER (recuerda guardar).";
                    statusColor = COLOR_ACCENT_LIGHT;
                } else {
                    statusMessage = p.name + " ya está en la lista; haz clic en su rol para cambiarlo.";
                    statusColor = COLOR_ACCENT_LIGHT;
                }
                addPlayerBox.setValue("");
                return;
            }
        }

        players.add(0, new EditablePlayer(raw, "BUILDER", "BUILDER", false, false));
        addPlayerBox.setValue("");
        playerScrollOffset = 0;
        dirty = true;
        statusMessage = raw + " añadido como BUILDER (recuerda guardar).";
        statusColor = COLOR_ACCENT_LIGHT;
    }

    private void cyclePlayerRole(EditablePlayer p) {
        if (p.forcedOpAdmin) {
            statusMessage = p.name + " tiene OP del servidor y siempre es ADMIN.";
            statusColor = COLOR_GOLD;
            return;
        }
        String current = p.assignedRole.isEmpty() ? p.effectiveRole : p.assignedRole;
        String next = switch (current.toUpperCase(Locale.ROOT)) {
            case "PLAYER" -> "BUILDER";
            case "BUILDER" -> "ADMIN";
            default -> "PLAYER";
        };
        p.assignedRole = next;
        p.effectiveRole = next;
        dirty = true;
        statusMessage = p.name + " -> " + next + " (pulsa Guardar para aplicar).";
        statusColor = COLOR_ACCENT_LIGHT;
    }

    private void removePlayerOverride(EditablePlayer p) {
        if (p.assignedRole.isEmpty()) {
            return;
        }
        if (p.online) {
            p.assignedRole = "";
            p.effectiveRole = p.forcedOpAdmin ? "ADMIN" : defaultRole;
        } else {
            players.remove(p);
            clampPlayerScroll();
        }
        dirty = true;
        statusMessage = "Se quitó el rol fijo de " + p.name + " (pulsa Guardar para aplicar).";
        statusColor = COLOR_ACCENT_LIGHT;
    }

    private void submitSave() {
        if (saving) {
            return;
        }
        saving = true;
        updateTabLabels();
        statusMessage = "Guardando configuración en el servidor...";
        statusColor = COLOR_ACCENT_LIGHT;

        List<PlayerAssignment> assignments = new ArrayList<>();
        for (EditablePlayer p : players) {
            if (p.assignedRole != null && !p.assignedRole.isBlank()) {
                assignments.add(new PlayerAssignment(p.name, p.assignedRole));
            }
        }

        ClientPlayNetworking.send(new SavePermissionsConfigPayload(
                defaultRole,
                fromArray(playerFlags),
                fromArray(builderFlags),
                assignments
        ));
    }

    public void handleSaveResult(boolean success, String message) {
        saving = false;
        if (success) {
            dirty = false;
        }
        updateTabLabels();
        statusMessage = message;
        statusColor = success ? COLOR_SUCCESS : COLOR_ERROR;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && addPlayerBox != null && addPlayerBox.isFocused()) {
            addOrPromotePlayer();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }

        if (tabPlayerButton.contains(mouseX, mouseY)) {
            playClickSound();
            editingRole = "PLAYER";
            updateTabLabels();
            return true;
        }

        if (tabBuilderButton.contains(mouseX, mouseY)) {
            playClickSound();
            editingRole = "BUILDER";
            updateTabLabels();
            return true;
        }

        if (defaultRoleButton.contains(mouseX, mouseY)) {
            playClickSound();
            cycleDefaultRole();
            return true;
        }

        if (addPlayerButton.contains(mouseX, mouseY)) {
            playClickSound();
            addOrPromotePlayer();
            return true;
        }

        if (backButton.contains(mouseX, mouseY)) {
            playClickSound();
            onClose();
            return true;
        }

        if (saveButton.contains(mouseX, mouseY)) {
            if (saveButton.active) {
                playClickSound();
                submitSave();
            }
            return true;
        }

        int rowX = leftX + 10;
        int rowW = Math.max(1, leftWidth - 20);
        int step = computeToggleStep();
        int rowH = Math.min(TOGGLE_HEIGHT, Math.max(18, step - 3));
        if (mouseX >= rowX && mouseX <= rowX + rowW) {
            boolean[] flags = currentFlags();
            for (int i = 0; i < PERM_LABELS.length; i++) {
                int ry = toggleStartY + i * step;
                if (mouseY >= ry && mouseY <= ry + rowH) {
                    playClickSound();
                    flags[i] = !flags[i];
                    dirty = true;
                    statusMessage = editingRole + " • " + PERM_LABELS[i] + ": " + (flags[i] ? "SÍ" : "NO");
                    statusColor = COLOR_ACCENT_LIGHT;
                    return true;
                }
            }
        }

        if (mouseX >= rightX + 6 && mouseX <= rightX + rightWidth - 6
                && mouseY >= playerListY && mouseY <= playerListY + playerListHeight) {
            int index = (int) ((mouseY - playerListY + playerScrollOffset) / PLAYER_ROW_HEIGHT);
            if (index >= 0 && index < players.size()) {
                EditablePlayer p = players.get(index);
                boolean hasRemove = !p.assignedRole.isEmpty();
                int removeBtnW = hasRemove ? 18 : 0;
                int removeBtnX = rightX + rightWidth - 12 - removeBtnW;
                int roleBtnW = 68;
                int roleBtnX = removeBtnX - (hasRemove ? 4 : 0) - roleBtnW;

                if (hasRemove && mouseX >= removeBtnX && mouseX <= removeBtnX + removeBtnW) {
                    playClickSound();
                    removePlayerOverride(p);
                    return true;
                }
                if (mouseX >= roleBtnX && mouseX <= roleBtnX + roleBtnW) {
                    playClickSound();
                    cyclePlayerRole(p);
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= rightX && mouseX <= rightX + rightWidth
                && mouseY >= playerListY && mouseY <= playerListY + playerListHeight) {
            playerScrollOffset -= scrollY * 18.0;
            clampPlayerScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void clampPlayerScroll() {
        double contentH = players.size() * PLAYER_ROW_HEIGHT;
        double max = Math.max(0, contentH - playerListHeight);
        playerScrollOffset = Math.max(0, Math.min(playerScrollOffset, max));
    }

    private int computeToggleStep() {
        int available = Math.max(120, contentBottom - toggleStartY - 6);
        return Math.min(TOGGLE_HEIGHT + TOGGLE_GAP, Math.max(20, available / PERM_LABELS.length));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, COLOR_BACKGROUND);
        graphics.renderOutline(panelLeft, panelTop, panelWidth, panelHeight, COLOR_BORDER);

        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1, panelTop + 43, COLOR_HEADER);
        graphics.fill(panelLeft + 1, panelTop + 42, panelLeft + panelWidth - 1, panelTop + 44, COLOR_ACCENT_DARK);
        graphics.fill(panelLeft + 16, panelTop + 12, panelLeft + 20, panelTop + 32, COLOR_GOLD);

        graphics.fill(leftX, contentY, leftX + leftWidth, contentBottom, COLOR_PANEL);
        graphics.renderOutline(leftX, contentY, leftWidth, Math.max(1, contentBottom - contentY), COLOR_BORDER);

        graphics.fill(rightX, contentY, rightX + rightWidth, contentBottom, COLOR_PANEL);
        graphics.renderOutline(rightX, contentY, rightWidth, Math.max(1, contentBottom - contentY), COLOR_BORDER);

        graphics.fill(panelLeft + 1, contentBottom + 1, panelLeft + panelWidth - 1, panelTop + panelHeight - 1, COLOR_HEADER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, panelLeft + 29, panelTop + 14, COLOR_TEXT, false);
        String badge = dirty ? "ADMIN • CAMBIOS SIN GUARDAR" : "ADMIN • EN VIVO";
        graphics.drawString(font, badge,
                panelLeft + panelWidth - 16 - font.width(badge),
                panelTop + 14,
                dirty ? COLOR_GOLD : COLOR_SUCCESS,
                false);

        drawButton(graphics, tabPlayerButton, mouseX, mouseY);
        drawButton(graphics, tabBuilderButton, mouseX, mouseY);
        drawButton(graphics, defaultRoleButton, mouseX, mouseY);

        renderRoleToggles(graphics, mouseX, mouseY);
        renderPlayersPanel(graphics, mouseX, mouseY);

        drawButton(graphics, addPlayerButton, mouseX, mouseY);
        drawButton(graphics, backButton, mouseX, mouseY);
        drawButton(graphics, saveButton, mouseX, mouseY);

        int statusMaxW = Math.max(1, panelWidth - 252);
        String clippedStatus = font.plainSubstrByWidth(statusMessage, statusMaxW);
        graphics.drawString(font, clippedStatus, panelLeft + 16, panelTop + panelHeight - 24, statusColor, false);
    }

    private void renderRoleToggles(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font,
                font.plainSubstrByWidth("PERMISOS DE: " + editingRole, Math.max(1, leftWidth - 20)),
                leftX + 10, contentY + 9, COLOR_ACCENT, false);

        int rowX = leftX + 10;
        int rowW = Math.max(1, leftWidth - 20);
        int step = computeToggleStep();
        int rowH = Math.min(TOGGLE_HEIGHT, Math.max(18, step - 3));
        boolean[] flags = currentFlags();

        for (int i = 0; i < PERM_LABELS.length; i++) {
            int ry = toggleStartY + i * step;
            boolean enabled = flags[i];
            boolean hovered = mouseX >= rowX && mouseX <= rowX + rowW
                    && mouseY >= ry && mouseY <= ry + rowH;

            graphics.fill(rowX, ry, rowX + rowW, ry + rowH, hovered ? 0xFF172C40 : COLOR_PANEL_INNER);
            graphics.renderOutline(rowX, ry, rowW, rowH, hovered ? COLOR_ACCENT : COLOR_BORDER);

            int pillW = 42;
            int pillX = rowX + rowW - pillW - 5;
            int pillY = ry + 3;
            int pillH = Math.max(10, rowH - 6);

            graphics.fill(pillX, pillY, pillX + pillW, pillY + pillH, enabled ? COLOR_SUCCESS_BG : COLOR_ERROR_BG);
            graphics.renderOutline(pillX, pillY, pillW, pillH, enabled ? COLOR_SUCCESS : COLOR_ERROR);
            graphics.drawCenteredString(font, enabled ? "SÍ" : "NO",
                    pillX + pillW / 2, ry + rowH / 2 - 4,
                    enabled ? COLOR_SUCCESS : COLOR_ERROR);

            int labelMaxW = Math.max(1, pillX - rowX - 12);
            String label = font.plainSubstrByWidth(PERM_LABELS[i], labelMaxW);
            graphics.drawString(font, label, rowX + 8, ry + rowH / 2 - 4, COLOR_TEXT, false);
        }
    }

    private void renderPlayersPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font,
                font.plainSubstrByWidth("JUGADORES Y ROLES", Math.max(1, rightWidth - 20)),
                rightX + 10, contentY + 10, COLOR_ACCENT, false);

        graphics.enableScissor(rightX + 4, playerListY, rightX + rightWidth - 4, playerListY + playerListHeight);

        if (players.isEmpty()) {
            graphics.drawWordWrap(font,
                    Component.literal("No hay jugadores asignados aún. Añade un nombre arriba."),
                    rightX + 12, playerListY + 12,
                    Math.max(1, rightWidth - 24), COLOR_MUTED);
        } else {
            for (int i = 0; i < players.size(); i++) {
                int y = (int) (playerListY + i * PLAYER_ROW_HEIGHT - playerScrollOffset);
                if (y + PLAYER_ROW_HEIGHT < playerListY || y > playerListY + playerListHeight) {
                    continue;
                }

                EditablePlayer p = players.get(i);
                int rowX = rightX + 8;
                int rowW = Math.max(1, rightWidth - 20);
                int rowH = PLAYER_ROW_HEIGHT - 3;

                graphics.fill(rowX, y, rowX + rowW, y + rowH, COLOR_PANEL_INNER);
                graphics.renderOutline(rowX, y, rowW, rowH, COLOR_BORDER);

                boolean hasRemove = !p.assignedRole.isEmpty();
                int removeBtnW = hasRemove ? 18 : 0;
                int removeBtnX = rowX + rowW - 4 - removeBtnW;
                int roleBtnW = 68;
                int roleBtnX = removeBtnX - (hasRemove ? 4 : 0) - roleBtnW;

                if (hasRemove) {
                    boolean hoverRem = mouseX >= removeBtnX && mouseX <= removeBtnX + removeBtnW
                            && mouseY >= Math.max(y + 3, playerListY)
                            && mouseY <= Math.min(y + rowH - 3, playerListY + playerListHeight);
                    graphics.fill(removeBtnX, y + 3, removeBtnX + removeBtnW, y + rowH - 3,
                            hoverRem ? 0xFF6A2E3B : 0xFF3E1C25);
                    graphics.renderOutline(removeBtnX, y + 3, removeBtnW, rowH - 6, COLOR_ERROR);
                    graphics.drawCenteredString(font, "X", removeBtnX + removeBtnW / 2, y + rowH / 2 - 4, COLOR_ERROR);
                }

                boolean hoverRole = mouseX >= roleBtnX && mouseX <= roleBtnX + roleBtnW
                        && mouseY >= Math.max(y + 3, playerListY)
                        && mouseY <= Math.min(y + rowH - 3, playerListY + playerListHeight);

                String roleLabel = p.forcedOpAdmin ? "OP/ADMIN" : p.effectiveRole;
                int roleCol = switch (p.effectiveRole.toUpperCase(Locale.ROOT)) {
                    case "ADMIN" -> COLOR_GOLD;
                    case "BUILDER" -> COLOR_ACCENT_LIGHT;
                    default -> COLOR_MUTED;
                };
                graphics.fill(roleBtnX, y + 3, roleBtnX + roleBtnW, y + rowH - 3,
                        hoverRole && !p.forcedOpAdmin ? 0xFF214969 : 0xFF14283B);
                graphics.renderOutline(roleBtnX, y + 3, roleBtnW, rowH - 6,
                        hoverRole && !p.forcedOpAdmin ? COLOR_ACCENT : COLOR_BORDER);
                graphics.drawCenteredString(font, roleLabel, roleBtnX + roleBtnW / 2, y + rowH / 2 - 4, roleCol);

                int dotColor = p.online ? COLOR_SUCCESS : COLOR_MUTED;
                graphics.fill(rowX + 6, y + rowH / 2 - 2, rowX + 10, y + rowH / 2 + 2, dotColor);

                int nameMaxW = Math.max(1, roleBtnX - (rowX + 16) - 4);
                String nameText = font.plainSubstrByWidth(p.name, nameMaxW);
                graphics.drawString(font, nameText, rowX + 15, y + rowH / 2 - 4,
                        p.online ? COLOR_TEXT : COLOR_MUTED, false);
            }
        }

        graphics.disableScissor();

        int totalH = players.size() * PLAYER_ROW_HEIGHT;
        if (totalH > playerListHeight && playerListHeight > 12) {
            int trackX = rightX + rightWidth - 6;
            graphics.fill(trackX, playerListY + 2, trackX + 2, playerListY + playerListHeight - 2, 0xFF183045);

            int barH = Math.max(16, (int) ((double) playerListHeight * playerListHeight / totalH));
            barH = Math.min(barH, Math.max(1, playerListHeight - 4));
            double maxScroll = Math.max(1, totalH - playerListHeight);
            double ratio = playerScrollOffset / maxScroll;
            int barY = playerListY + 2 + (int) (ratio * Math.max(0, playerListHeight - barH - 4));
            graphics.fill(trackX, barY, trackX + 2, barY + barH, COLOR_ACCENT);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
            if (parent instanceof ServerLibraryScreen) {
                ClientPlayNetworking.send(new RequestServerLibraryPayload());
            }
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}