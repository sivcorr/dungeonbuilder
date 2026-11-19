package dev.dungeonbuilder.gui;

import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.loot.LootModels.LootEntry;
import dev.dungeonbuilder.loot.LootModels.LootProfile;
import dev.dungeonbuilder.loot.LootProfileStorage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Editor visual de perfiles de loot (con íconos reales e ítems directos desde tu inventario). */
public class LootProfilesScreen extends Screen {

    private static final int PROFILE_ROW_H = 26;
    private static final int ENTRY_ROW_H = 24;

    private static final int COLOR_BACKGROUND = 0xF00B1611;
    private static final int COLOR_HEADER = 0xFF132A1F;
    private static final int COLOR_PANEL = 0xF0101F18;
    private static final int COLOR_PANEL_INNER = 0xFF0C1812;

    private static final int COLOR_ACCENT = 0xFF4ADE80;
    private static final int COLOR_ACCENT_LIGHT = 0xFFA7F3D0;
    private static final int COLOR_ACCENT_DARK = 0xFF256D49;
    private static final int COLOR_GOLD = 0xFFFACC15;

    private static final int COLOR_TEXT = 0xFFEEFBF3;
    private static final int COLOR_MUTED = 0xFF93B3A1;
    private static final int COLOR_BORDER = 0xFF2B5942;
    private static final int COLOR_ERROR = 0xFFFF8585;

    private final Screen parent;
    private final List<LootProfile> profiles = new ArrayList<>();
    private final Map<String, ItemStack> stackCache = new HashMap<>();

    private LootProfile selected;
    private double profileScroll;
    private double entryScroll;
    private long confirmDeleteUntilMs;

    private EditBox newProfileBox;
    private String statusMessage = "Haz clic en un objeto de tu inventario (abajo) para añadirlo al perfil.";
    private int statusColor = COLOR_MUTED;

    private int panelLeft, panelTop, panelWidth, panelHeight;
    private int leftX, leftWidth, rightX, rightWidth, topY, contentBottom;
    private int profileListY, profileListH;
    private int entriesY, entriesH;
    private int invGridX, invGridY;

    private UiButton addProfileBtn;
    private UiButton deleteProfileBtn;
    private UiButton backBtn;
    private UiButton saveBtn;

    public LootProfilesScreen(Screen parent) {
        super(Component.literal("Editor de perfiles de loot"));
        this.parent = parent;
        for (LootProfile p : LootProfileStorage.listProfiles()) {
            this.profiles.add(p.copy());
        }
        this.selected = this.profiles.isEmpty() ? null : this.profiles.get(0);
    }

    @Override
    protected void init() {
        panelWidth = Math.max(1, Math.min(880, width - 12));
        panelHeight = Math.max(1, Math.min(440, height - 12));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int innerW = Math.max(1, panelWidth - 32);
        leftX = panelLeft + 16;
        leftWidth = Math.max(1, (int) (innerW * 0.30));
        rightX = leftX + leftWidth + 12;
        rightWidth = Math.max(1, panelLeft + panelWidth - 16 - rightX);

        topY = panelTop + 52;
        contentBottom = panelTop + panelHeight - 40;

        String prev = newProfileBox != null ? newProfileBox.getValue() : "";
        int addW = Math.min(72, Math.max(52, leftWidth / 3));
        int boxW = Math.max(1, leftWidth - 20 - addW - 6);

        newProfileBox = new EditBox(font, leftX + 10, topY + 10, boxW, 21, Component.literal("Nuevo perfil"));
        newProfileBox.setMaxLength(28);
        newProfileBox.setHint(Component.literal("Nuevo perfil..."));
        newProfileBox.setValue(prev);
        addRenderableWidget(newProfileBox);

        addProfileBtn = new UiButton(leftX + 10 + boxW + 6, topY + 9, addW, 23,
                Component.literal("Crear"), "+", ButtonStyle.SECONDARY);

        profileListY = topY + 38;
        profileListH = Math.max(40, contentBottom - profileListY - 34);

        deleteProfileBtn = new UiButton(leftX + 10, contentBottom - 28, Math.max(1, leftWidth - 20), 21,
                Component.literal("Eliminar perfil"), "X", ButtonStyle.DANGER);

        entriesY = topY + 30;
        int invSectionH = 92;
        entriesH = Math.max(48, contentBottom - entriesY - invSectionH - 12);

        invGridX = rightX + 12;
        invGridY = contentBottom - 78;

        backBtn = new UiButton(panelLeft + panelWidth - 216, panelTop + panelHeight - 31,
                84, 22, Component.literal("Volver"), "<", ButtonStyle.SECONDARY);
        saveBtn = new UiButton(panelLeft + panelWidth - 124, panelTop + panelHeight - 31,
                108, 22, Component.literal("Guardar"), "S", ButtonStyle.PRIMARY);
    }

    private ItemStack resolveStack(String snbt) {
        if (minecraft == null || minecraft.level == null || snbt == null || snbt.isBlank()) {
            return ItemStack.EMPTY;
        }
        return stackCache.computeIfAbsent(snbt,
                s -> LootProfileStorage.snbtToItemStack(s, minecraft.level.registryAccess()));
    }

    private void createProfile() {
        if (newProfileBox == null) return;
        String name = newProfileBox.getValue().trim();
        if (name.isEmpty()) {
            statusMessage = "Escribe un nombre para el nuevo perfil.";
            statusColor = COLOR_ERROR;
            return;
        }
        String id = LootProfileStorage.uniqueId(name, profiles);
        LootProfile p = new LootProfile(id, name, 3, 6, new ArrayList<>());
        profiles.add(p);
        selected = p;
        newProfileBox.setValue("");
        statusMessage = "Perfil '" + name + "' creado. Haz clic en tus ítems abajo para añadirlos.";
        statusColor = COLOR_ACCENT_LIGHT;
    }

    private void deleteSelectedProfile() {
        if (selected == null) return;
        if (profiles.size() <= 1) {
            statusMessage = "Debe existir al menos 1 perfil de loot.";
            statusColor = COLOR_ERROR;
            return;
        }
        long now = System.currentTimeMillis();
        if (now > confirmDeleteUntilMs) {
            confirmDeleteUntilMs = now + 3500L;
            deleteProfileBtn.label = Component.literal("¿Confirmar?");
            return;
        }
        confirmDeleteUntilMs = 0;
        deleteProfileBtn.label = Component.literal("Eliminar perfil");
        profiles.remove(selected);
        selected = profiles.get(0);
    }

    private void addItemFromInventory(ItemStack stack) {
        if (selected == null || stack == null || stack.isEmpty() || minecraft == null || minecraft.level == null) {
            return;
        }
        String snbt = LootProfileStorage.itemStackToSnbt(stack, minecraft.level.registryAccess());
        if (snbt.isEmpty()) {
            return;
        }
        int maxC = Math.max(1, Math.min(stack.getMaxStackSize(), stack.getCount()));
        selected.entries.add(new LootEntry(snbt, 30, 1, maxC));
        statusMessage = "Añadido: " + stack.getHoverName().getString() + " (30%, 1-" + maxC + ")";
        statusColor = COLOR_ACCENT_LIGHT;
    }

    private void saveProfiles() {
        try {
            LootProfileStorage.saveProfiles(profiles);
            statusMessage = "¡Perfiles de loot guardados!";
            statusColor = COLOR_ACCENT_LIGHT;
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error guardando loot_profiles.json", e);
            statusMessage = "Error guardando los perfiles.";
            statusColor = COLOR_ERROR;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (confirmDeleteUntilMs > 0 && System.currentTimeMillis() > confirmDeleteUntilMs) {
            confirmDeleteUntilMs = 0;
            if (deleteProfileBtn != null) {
                deleteProfileBtn.label = Component.literal("Eliminar perfil");
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && newProfileBox != null && newProfileBox.isFocused()) {
            createProfile();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 && button != 1) {
            return false;
        }

        if (button == 0) {
            if (addProfileBtn.contains(mouseX, mouseY)) {
                playClickSound();
                createProfile();
                return true;
            }
            if (deleteProfileBtn.contains(mouseX, mouseY)) {
                playClickSound();
                deleteSelectedProfile();
                return true;
            }
            if (backBtn.contains(mouseX, mouseY)) {
                playClickSound();
                onClose();
                return true;
            }
            if (saveBtn.contains(mouseX, mouseY)) {
                playClickSound();
                saveProfiles();
                return true;
            }

            // Selección de perfil
            if (mouseX >= leftX + 6 && mouseX <= leftX + leftWidth - 6
                    && mouseY >= profileListY && mouseY <= profileListY + profileListH) {
                int idx = (int) ((mouseY - profileListY + profileScroll) / PROFILE_ROW_H);
                if (idx >= 0 && idx < profiles.size()) {
                    playClickSound();
                    selected = profiles.get(idx);
                    entryScroll = 0;
                    return true;
                }
            }

            // Controles de Mín / Máx rolls del perfil
            if (selected != null && mouseY >= topY + 6 && mouseY <= topY + 24) {
                int ctrlX = rightX + rightWidth - 196;
                if (mouseX >= ctrlX && mouseX <= ctrlX + 16) {
                    selected.minRolls = Math.max(1, selected.minRolls - 1);
                    playClickSound();
                    return true;
                }
                if (mouseX >= ctrlX + 46 && mouseX <= ctrlX + 62) {
                    selected.minRolls = Math.min(27, selected.minRolls + 1);
                    if (selected.maxRolls < selected.minRolls) selected.maxRolls = selected.minRolls;
                    playClickSound();
                    return true;
                }
                if (mouseX >= ctrlX + 98 && mouseX <= ctrlX + 114) {
                    selected.maxRolls = Math.max(selected.minRolls, selected.maxRolls - 1);
                    playClickSound();
                    return true;
                }
                if (mouseX >= ctrlX + 144 && mouseX <= ctrlX + 160) {
                    selected.maxRolls = Math.min(27, selected.maxRolls + 1);
                    playClickSound();
                    return true;
                }
            }

            // Clic en slots del inventario del jugador (9x4)
            if (minecraft != null && minecraft.player != null) {
                for (int slot = 0; slot < 36; slot++) {
                    int row = slot < 9 ? 3 : (slot - 9) / 9;
                    int col = slot < 9 ? slot : (slot - 9) % 9;
                    int sx = invGridX + col * 20;
                    int sy = invGridY + row * 18;
                    if (mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18) {
                        ItemStack stack = minecraft.player.getInventory().getItem(slot);
                        if (!stack.isEmpty()) {
                            playClickSound();
                            addItemFromInventory(stack);
                        }
                        return true;
                    }
                }
            }
        }

        // Controles de las filas de ítems del perfil (Peso % y Cant y Eliminar)
        if (selected != null && mouseX >= rightX + 8 && mouseX <= rightX + rightWidth - 8
                && mouseY >= entriesY && mouseY <= entriesY + entriesH) {
            int idx = (int) ((mouseY - entriesY + entryScroll) / ENTRY_ROW_H);
            if (idx >= 0 && idx < selected.entries.size()) {
                LootEntry e = selected.entries.get(idx);
                int rowR = rightX + rightWidth - 12;
                int delX = rowR - 18;
                int qtyPlusX = delX - 20;
                int qtyMinusX = qtyPlusX - 48;
                int pctPlusX = qtyMinusX - 22;
                int pctMinusX = pctPlusX - 46;
                int step = Screen.hasShiftDown() ? 1 : 5;

                if (button == 0 && mouseX >= delX && mouseX <= delX + 16) {
                    playClickSound();
                    selected.entries.remove(idx);
                    return true;
                }
                if (mouseX >= pctMinusX && mouseX <= pctMinusX + 14) {
                    playClickSound();
                    e.chancePercent = Math.max(1, e.chancePercent - step);
                    return true;
                }
                if (mouseX >= pctPlusX && mouseX <= pctPlusX + 14) {
                    playClickSound();
                    e.chancePercent = Math.min(100, e.chancePercent + step);
                    return true;
                }
                if (mouseX >= qtyMinusX && mouseX <= qtyMinusX + 14) {
                    playClickSound();
                    if (Screen.hasShiftDown()) {
                        e.minCount = Math.max(1, e.minCount - 1);
                    } else if (e.maxCount > e.minCount) {
                        e.maxCount--;
                    } else if (e.minCount > 1) {
                        e.minCount--;
                        e.maxCount--;
                    }
                    return true;
                }
                if (mouseX >= qtyPlusX && mouseX <= qtyPlusX + 14) {
                    playClickSound();
                    int limit = Math.max(1, resolveStack(e.itemSnbt).getMaxStackSize());
                    if (Screen.hasShiftDown()) {
                        e.minCount = Math.min(e.maxCount, e.minCount + 1);
                    } else {
                        e.maxCount = Math.min(limit, e.maxCount + 1);
                    }
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= leftX && mouseX <= leftX + leftWidth
                && mouseY >= profileListY && mouseY <= profileListY + profileListH) {
            profileScroll -= scrollY * 16.0;
            double max = Math.max(0, profiles.size() * PROFILE_ROW_H - profileListH);
            profileScroll = Math.max(0, Math.min(profileScroll, max));
            return true;
        }
        if (selected != null && mouseX >= rightX && mouseX <= rightX + rightWidth
                && mouseY >= entriesY && mouseY <= entriesY + entriesH) {
            entryScroll -= scrollY * 16.0;
            double max = Math.max(0, selected.entries.size() * ENTRY_ROW_H - entriesH);
            entryScroll = Math.max(0, Math.min(entryScroll, max));
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
        graphics.fill(panelLeft + 16, panelTop + 12, panelLeft + 20, panelTop + 32, COLOR_GOLD);

        graphics.fill(leftX, topY, leftX + leftWidth, contentBottom, COLOR_PANEL);
        graphics.renderOutline(leftX, topY, leftWidth, Math.max(1, contentBottom - topY), COLOR_BORDER);

        graphics.fill(rightX, topY, rightX + rightWidth, contentBottom, COLOR_PANEL);
        graphics.renderOutline(rightX, topY, rightWidth, Math.max(1, contentBottom - topY), COLOR_BORDER);

        graphics.fill(panelLeft + 1, contentBottom + 1, panelLeft + panelWidth - 1, panelTop + panelHeight - 1, COLOR_HEADER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, panelLeft + 29, panelTop + 14, COLOR_TEXT, false);
        String hintTop = "Shift + clic en [-]/[+] ajusta de 1 en 1";
        graphics.drawString(font, hintTop, panelLeft + panelWidth - 16 - font.width(hintTop),
                panelTop + 14, COLOR_MUTED, false);

        renderProfileList(graphics, mouseX, mouseY);
        ItemStack hovered = renderRightEditor(graphics, mouseX, mouseY);

        drawButton(graphics, addProfileBtn, mouseX, mouseY);
        drawButton(graphics, deleteProfileBtn, mouseX, mouseY);
        drawButton(graphics, backBtn, mouseX, mouseY);
        drawButton(graphics, saveBtn, mouseX, mouseY);

        int statusMaxW = Math.max(1, backBtn.x - (panelLeft + 16) - 10);
        graphics.drawString(font, font.plainSubstrByWidth(statusMessage, statusMaxW),
                panelLeft + 16, panelTop + panelHeight - 24, statusColor, false);

        if (hovered != null && !hovered.isEmpty()) {
            graphics.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    private void renderProfileList(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.enableScissor(leftX + 4, profileListY, leftX + leftWidth - 4, profileListY + profileListH);
        for (int i = 0; i < profiles.size(); i++) {
            int y = (int) (profileListY + i * PROFILE_ROW_H - profileScroll);
            if (y + PROFILE_ROW_H < profileListY || y > profileListY + profileListH) continue;

            LootProfile p = profiles.get(i);
            boolean isSel = selected == p;
            boolean hov = mouseX >= leftX + 6 && mouseX <= leftX + leftWidth - 6
                    && mouseY >= Math.max(y, profileListY) && mouseY <= Math.min(y + PROFILE_ROW_H, profileListY + profileListH);

            int rl = leftX + 8;
            int rr = leftX + leftWidth - 8;
            if (isSel) {
                graphics.fill(rl, y + 2, rr, y + PROFILE_ROW_H - 2, 0xFF1B3A2B);
                graphics.renderOutline(rl, y + 2, Math.max(1, rr - rl), PROFILE_ROW_H - 4, COLOR_ACCENT);
            } else if (hov) {
                graphics.fill(rl, y + 2, rr, y + PROFILE_ROW_H - 2, 0xFF162C22);
            }

            String txt = p.name + " (" + p.entries.size() + ")";
            graphics.drawString(font, font.plainSubstrByWidth(txt, Math.max(1, rr - rl - 12)),
                    rl + 6, y + 8, isSel ? COLOR_ACCENT_LIGHT : COLOR_TEXT, false);
        }
        graphics.disableScissor();
    }

    private ItemStack renderRightEditor(GuiGraphics graphics, int mouseX, int mouseY) {
        ItemStack hovered = ItemStack.EMPTY;
        if (selected == null) {
            return hovered;
        }

        graphics.drawString(font,
                font.plainSubstrByWidth("ÍTEMS EN: " + selected.name, Math.max(1, rightWidth - 220)),
                rightX + 10, topY + 10, COLOR_ACCENT, false);

        // Controles de Mín / Máx rolls por cofre
        int ctrlX = rightX + rightWidth - 196;
        int cy = topY + 7;
        graphics.drawString(font, "Mín:", ctrlX - 24, cy + 4, COLOR_MUTED, false);
        drawMiniBtn(graphics, ctrlX, cy, "-", mouseX, mouseY);
        graphics.drawCenteredString(font, String.valueOf(selected.minRolls), ctrlX + 31, cy + 4, COLOR_TEXT);
        drawMiniBtn(graphics, ctrlX + 46, cy, "+", mouseX, mouseY);

        graphics.drawString(font, "Máx:", ctrlX + 72, cy + 4, COLOR_MUTED, false);
        drawMiniBtn(graphics, ctrlX + 98, cy, "-", mouseX, mouseY);
        graphics.drawCenteredString(font, String.valueOf(selected.maxRolls), ctrlX + 129, cy + 4, COLOR_TEXT);
        drawMiniBtn(graphics, ctrlX + 144, cy, "+", mouseX, mouseY);

        // Lista de entradas del perfil
        graphics.fill(rightX + 8, entriesY, rightX + rightWidth - 8, entriesY + entriesH, COLOR_PANEL_INNER);
        graphics.renderOutline(rightX + 8, entriesY, Math.max(1, rightWidth - 16), entriesH, COLOR_BORDER);

        graphics.enableScissor(rightX + 9, entriesY + 1, rightX + rightWidth - 9, entriesY + entriesH - 1);
        for (int i = 0; i < selected.entries.size(); i++) {
            int y = (int) (entriesY + i * ENTRY_ROW_H - entryScroll);
            if (y + ENTRY_ROW_H < entriesY || y > entriesY + entriesH) continue;

            LootEntry e = selected.entries.get(i);
            ItemStack stack = resolveStack(e.itemSnbt);

            int rowL = rightX + 12;
            int rowR = rightX + rightWidth - 12;
            int delX = rowR - 18;
            int qtyPlusX = delX - 20;
            int qtyMinusX = qtyPlusX - 48;
            int pctPlusX = qtyMinusX - 22;
            int pctMinusX = pctPlusX - 46;

            if (i % 2 == 1) {
                graphics.fill(rowL, y + 1, rowR, y + ENTRY_ROW_H - 1, 0x331B3A2B);
            }

            if (!stack.isEmpty()) {
                graphics.renderItem(stack, rowL + 2, y + 4);
                if (mouseX >= rowL + 2 && mouseX < rowL + 18 && mouseY >= y + 4 && mouseY < y + 20) {
                    hovered = stack;
                }
            }
            String iName = stack.isEmpty() ? "(Desconocido)" : stack.getHoverName().getString();
            graphics.drawString(font,
                    font.plainSubstrByWidth(iName, Math.max(1, pctMinusX - (rowL + 24) - 6)),
                    rowL + 23, y + 8, COLOR_TEXT, false);

            int by = y + 4;
            drawMiniBtn(graphics, pctMinusX, by, "-", mouseX, mouseY);
            graphics.drawCenteredString(font, e.chancePercent + "%", pctMinusX + 30, y + 8, COLOR_GOLD);
            drawMiniBtn(graphics, pctPlusX, by, "+", mouseX, mouseY);

            drawMiniBtn(graphics, qtyMinusX, by, "-", mouseX, mouseY);
            String qtyStr = e.minCount == e.maxCount ? ("x" + e.minCount) : (e.minCount + "-" + e.maxCount);
            graphics.drawCenteredString(font, qtyStr, qtyMinusX + 31, y + 8, COLOR_ACCENT_LIGHT);
            drawMiniBtn(graphics, qtyPlusX, by, "+", mouseX, mouseY);

            boolean hovDel = mouseX >= delX && mouseX <= delX + 16 && mouseY >= by && mouseY <= by + 16;
            graphics.fill(delX, by, delX + 16, by + 16, hovDel ? 0xFF6A2E3B : 0xFF3E1C25);
            graphics.renderOutline(delX, by, 16, 16, COLOR_ERROR);
            graphics.drawCenteredString(font, "X", delX + 8, y + 8, COLOR_ERROR);
        }
        graphics.disableScissor();

        // Inventario del jugador abajo
        graphics.drawString(font, "TU INVENTARIO (clic en un objeto para añadirlo al perfil):",
                rightX + 10, invGridY - 13, COLOR_ACCENT_LIGHT, false);

        if (minecraft != null && minecraft.player != null) {
            for (int slot = 0; slot < 36; slot++) {
                int row = slot < 9 ? 3 : (slot - 9) / 9;
                int col = slot < 9 ? slot : (slot - 9) % 9;
                int sx = invGridX + col * 20;
                int sy = invGridY + row * 18;
                boolean hov = mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18;

                graphics.fill(sx, sy, sx + 18, sy + 18, hov ? 0xFF234D38 : COLOR_PANEL_INNER);
                graphics.renderOutline(sx, sy, 18, 18, hov ? COLOR_ACCENT : COLOR_BORDER);

                ItemStack stack = minecraft.player.getInventory().getItem(slot);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, sx + 1, sy + 1);
                    graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
                    if (hov) {
                        hovered = stack;
                    }
                }
            }
        }

        return hovered;
    }

    private void drawMiniBtn(GuiGraphics g, int x, int y, String label, int mx, int my) {
        boolean hov = mx >= x && mx <= x + 14 && my >= y && my <= y + 16;
        g.fill(x, y, x + 14, y + 16, hov ? 0xFF297550 : 0xFF172E23);
        g.renderOutline(x, y, 14, 16, hov ? COLOR_ACCENT : COLOR_BORDER);
        g.drawCenteredString(font, label, x + 7, y + 4, COLOR_TEXT);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
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
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.label = label; this.icon = icon; this.style = style;
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
        } else if (button.style == ButtonStyle.DANGER) {
            bg = hovered ? 0xFF6A2E3B : 0xFF42232B; border = hovered ? 0xFFFF9FB0 : 0xFF9B5565;
            accent = 0xFFFF9FB0; textCol = COLOR_TEXT; iconBg = 0x664A1E28;
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