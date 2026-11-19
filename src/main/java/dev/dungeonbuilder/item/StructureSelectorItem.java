package dev.dungeonbuilder.item;

import dev.dungeonbuilder.network.ModNetworking.OpenSelectorSavePayload;
import dev.dungeonbuilder.selection.SelectionData;
import dev.dungeonbuilder.selection.SelectionManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Herramienta dedicada a marcar dos esquinas y abrir la interfaz para guardar
 * la estructura resultante en la biblioteca personal local ("Estructuras mías").
 */
public class StructureSelectorItem extends Item {
    public StructureSelectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (SelectionManager.hasCompleteSelectionIn(player.getUUID(), level.dimension())) {
                SelectionData sel = SelectionManager.getSelection(player.getUUID());
                ServerPlayNetworking.send(serverPlayer, new OpenSelectorSavePayload(
                        sel.min(), sel.max(), sel.width(), sel.height(), sel.depth()
                ));
            } else if (SelectionManager.hasFirstCornerIn(player.getUUID(), level.dimension())) {
                player.displayClientMessage(prefix(ChatFormatting.YELLOW)
                        .append(Component.translatable("message.dungeonbuilder.selector_need_second")
                                .withStyle(ChatFormatting.WHITE)), true);
            } else {
                player.displayClientMessage(prefix(ChatFormatting.YELLOW)
                        .append(Component.translatable("message.dungeonbuilder.selector_help")
                                .withStyle(ChatFormatting.WHITE)), true);
                player.displayClientMessage(Component.translatable("message.dungeonbuilder.selector_cancel_hint")
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    private static net.minecraft.network.chat.MutableComponent prefix(ChatFormatting color) {
        return Component.literal("DungeonBuilder ").withStyle(color)
                .append(Component.literal("» ").withStyle(ChatFormatting.GRAY));
    }
}