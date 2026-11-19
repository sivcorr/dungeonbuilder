package dev.dungeonbuilder.item;

import dev.dungeonbuilder.network.ModNetworking;
import dev.dungeonbuilder.network.ModNetworking.OpenServerLibraryPayload;
import dev.dungeonbuilder.permissions.PermissionsManager;
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

/** Abre la biblioteca compartida del servidor. */
public class StructureLibraryItem extends Item {

    public StructureLibraryItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level,
            Player player,
            InteractionHand hand
    ) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (!PermissionsManager.canView(serverPlayer)) {
                serverPlayer.displayClientMessage(
                        Component.literal("DungeonBuilder ").withStyle(ChatFormatting.RED)
                                .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal("No tienes permisos para consultar la biblioteca del servidor.")
                                        .withStyle(ChatFormatting.RED)),
                        true
                );
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }

            ServerPlayNetworking.send(
                    serverPlayer,
                    new OpenServerLibraryPayload(
                            PermissionsManager.canPreview(serverPlayer),
                            ModNetworking.canPlaceServerStructure(serverPlayer),
                            ModNetworking.canDeleteServerStructure(serverPlayer),
                            ModNetworking.canEditServerLoot(serverPlayer),
                            PermissionsManager.getRole(serverPlayer).name(),
                            ModNetworking.buildServerEntryInfoList()
                    )
            );
        }

        return InteractionResultHolder.sidedSuccess(
                player.getItemInHand(hand),
                level.isClientSide()
        );
    }
}