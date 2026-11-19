package dev.dungeonbuilder.gui;

import dev.dungeonbuilder.network.ModNetworking.RequestSaveStructurePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

public class SelectorSaveScreen extends Screen {

    // =====================================================
    // ESTILO MORADO (SELECCIONADOR)
    // =====================================================

    private static final int BG = 0xF0100D19;
    private static final int HEADER = 0xFF241A35;
    private static final int CARD_BG = 0xFF1D1728;
    private static final int PURPLE = 0xFFB77AFF;
    private static final int PURPLE_LIGHT = 0xFFD6B2FF;
    private static final int PURPLE_DARK = 0xFF684494;
    private static final int TEXT = 0xFFF4EFFA;
    private static final int MUTED = 0xFFAAA0BA;
    private static final int BORDER = 0xFF49345F;

    private static final Component THANKS =
            Component.literal("¡Gracias por usar Dungeon Builder!");

    // =====================================================
    // DATOS
    // =====================================================

    private final BlockPos min;
    private final BlockPos max;

    private final int widthBlocks;
    private final int heightBlocks;
    private final int depthBlocks;

    private EditBox nameBox;

    private UiButton saveButton;
    private UiButton cancelButton;

    private Component statusMessage = Component.empty();
    private int statusColor = 0xFFFF8585;
    private boolean saving = false;

    // =====================================================
    // DISTRIBUCIÓN
    // =====================================================

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    private int nameY;
    private int inputY;
    private int statusY;
    private int buttonY;
    private int footerY;

    public SelectorSaveScreen(
            BlockPos min,
            BlockPos max,
            int widthBlocks,
            int heightBlocks,
            int depthBlocks
    ) {
        super(Component.translatable("gui.dungeonbuilder.selector.title"));
        this.min = min;
        this.max = max;
        this.widthBlocks = widthBlocks;
        this.heightBlocks = heightBlocks;
        this.depthBlocks = depthBlocks;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(380, Math.max(1, width - 16));
        panelHeight = Math.min(210, Math.max(1, height - 16));

        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        boolean compact = panelWidth < 320 || panelHeight < 190;

        nameY = compact ? 67 : 78;
        inputY = nameY + 14;

        buttonY = panelTop + panelHeight - 52;
        footerY = panelTop + panelHeight - 17;

        statusY = Math.min(panelTop + inputY + 27, buttonY - 17);

        String previousName = nameBox != null ? nameBox.getValue() : "";

        nameBox = new EditBox(
                font,
                panelLeft + 16,
                panelTop + inputY,
                Math.max(1, panelWidth - 32),
                22,
                Component.translatable("gui.dungeonbuilder.selector.name_label")
        );
        nameBox.setMaxLength(48);
        nameBox.setHint(Component.translatable("gui.dungeonbuilder.selector.name_hint"));
        nameBox.setValue(previousName);
        addRenderableWidget(nameBox);

        setFocused(nameBox);
        nameBox.setFocused(true);

        int buttonGap = 10;
        int buttonWidth = Math.max(1, (panelWidth - 32 - buttonGap) / 2);

        saveButton = new UiButton(
                panelLeft + 16,
                buttonY,
                buttonWidth,
                23,
                Component.translatable("gui.dungeonbuilder.selector.save"),
                "S",
                ButtonStyle.PRIMARY
        );

        cancelButton = new UiButton(
                panelLeft + 16 + buttonWidth + buttonGap,
                buttonY,
                buttonWidth,
                23,
                Component.translatable("gui.dungeonbuilder.common.cancel"),
                "X",
                ButtonStyle.SECONDARY
        );
    }

    private void submitSave() {
        if (saving) {
            return;
        }

        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            showError(Component.translatable("gui.dungeonbuilder.selector.error_empty_name"));
            nameBox.setFocused(true);
            return;
        }

        saving = true;
        saveButton.active = false;
        statusMessage = Component.translatable("gui.dungeonbuilder.selector.saving");
        statusColor = PURPLE_LIGHT;

        ClientPlayNetworking.send(new RequestSaveStructurePayload(name));
    }

    public void showError(Component message) {
        saving = false;
        if (saveButton != null) {
            saveButton.active = true;
        }
        statusMessage = message;
        statusColor = 0xFFFF8585;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && nameBox != null && nameBox.isFocused()) {
            submitSave();
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

        if (saveButton.contains(mouseX, mouseY)) {
            if (saveButton.active) {
                playClickSound();
                submitSave();
            }
            return true;
        }

        if (cancelButton.contains(mouseX, mouseY)) {
            playClickSound();
            onClose();
            return true;
        }

        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, BG);
        graphics.renderOutline(panelLeft, panelTop, panelWidth, panelHeight, BORDER);

        // Cabecera
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1, panelTop + 32, HEADER);
        graphics.fill(panelLeft + 1, panelTop + 31, panelLeft + panelWidth - 1, panelTop + 33, PURPLE_DARK);
        graphics.fill(panelLeft + 14, panelTop + 10, panelLeft + 18, panelTop + 24, PURPLE);

        // Tarjeta de dimensiones
        graphics.fill(panelLeft + 16, panelTop + 37, panelLeft + panelWidth - 16, panelTop + 59, CARD_BG);
        graphics.renderOutline(panelLeft + 16, panelTop + 37, Math.max(1, panelWidth - 32), 22, BORDER);

        // Pie de la interfaz
        graphics.fill(panelLeft + 1, footerY - 6, panelLeft + panelWidth - 1, panelTop + panelHeight - 1, HEADER);
        graphics.fill(panelLeft + 16, footerY - 6, panelLeft + panelWidth - 16, footerY - 5, PURPLE_DARK);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, panelLeft + 27, panelTop + 11, TEXT, false);

        long volume = (long) widthBlocks * heightBlocks * depthBlocks;
        Component dimensions = Component.translatable(
                "gui.dungeonbuilder.selector.dimensions",
                widthBlocks, heightBlocks, depthBlocks, volume
        );
        graphics.drawString(
                font,
                font.plainSubstrByWidth(dimensions.getString(), Math.max(1, panelWidth - 40)),
                panelLeft + 23,
                panelTop + 44,
                PURPLE_LIGHT,
                false
        );

        Component coordinates = Component.translatable(
                "gui.dungeonbuilder.selector.coords",
                min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ()
        );
        graphics.drawWordWrap(font, coordinates, panelLeft + 16, panelTop + 63, Math.max(1, panelWidth - 32), MUTED);

        graphics.drawString(
                font,
                Component.translatable("gui.dungeonbuilder.selector.name_label"),
                panelLeft + 16,
                panelTop + nameY,
                TEXT,
                false
        );

        if (!statusMessage.getString().isEmpty()) {
            graphics.drawWordWrap(font, statusMessage, panelLeft + 16, statusY, Math.max(1, panelWidth - 32), statusColor);
        }

        drawButton(graphics, saveButton, mouseX, mouseY);
        drawButton(graphics, cancelButton, mouseX, mouseY);

        graphics.drawCenteredString(font, THANKS, width / 2, footerY + 2, PURPLE_DARK);
        graphics.drawCenteredString(font, THANKS, width / 2, footerY + 1, PURPLE_LIGHT);
    }

    private enum ButtonStyle {
        PRIMARY,
        SECONDARY
    }

    private static class UiButton {
        final int x, y, width, height;
        final Component label;
        final String icon;
        final ButtonStyle style;
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

        boolean hovered = button.contains(mouseX, mouseY) && button.active;

        int background;
        int border;
        int accent;
        int textColor;

        if (!button.active) {
            background = 0xFF1C1824;
            border = 0xFF383040;
            accent = 0xFF655B70;
            textColor = 0xFF756C80;
        } else if (button.style == ButtonStyle.PRIMARY) {
            background = hovered ? 0xFF684493 : 0xFF493064;
            border = hovered ? PURPLE_LIGHT : PURPLE;
            accent = PURPLE_LIGHT;
            textColor = TEXT;
        } else {
            background = hovered ? 0xFF3C2B50 : 0xFF292135;
            border = hovered ? PURPLE : BORDER;
            accent = PURPLE;
            textColor = TEXT;
        }

        graphics.fill(button.x, button.y + 3, button.x + button.width, button.y + button.height + 3, 0x99000000);
        graphics.fill(button.x, button.y, button.x + button.width, button.y + button.height, background);
        graphics.renderOutline(button.x, button.y, button.width, button.height, border);
        graphics.fill(button.x + 2, button.y + 2, button.x + button.width - 2, button.y + 4, accent);

        graphics.fill(button.x + 5, button.y + 5, button.x + 22, button.y + button.height - 5, 0x663E2A58);
        graphics.drawCenteredString(font, button.icon, button.x + 13, button.y + button.height / 2 - 4, accent);

        String text = font.plainSubstrByWidth(button.label.getString(), Math.max(1, button.width - 34));
        graphics.drawCenteredString(font, text, button.x + button.width / 2 + 7, button.y + button.height / 2 - 4, textColor);
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