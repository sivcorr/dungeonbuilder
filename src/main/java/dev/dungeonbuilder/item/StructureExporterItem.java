package dev.dungeonbuilder.item;

import dev.dungeonbuilder.export.ExporterManager;
import dev.dungeonbuilder.network.ModNetworking.OpenExporterPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Abre el menú del Exportador de estructuras. */
public class StructureExporterItem extends Item {

    public StructureExporterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level,
            Player player,
            InteractionHand hand
    ) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            boolean canExport = ExporterManager.canExport(serverPlayer);
            ServerPlayNetworking.send(serverPlayer, new OpenExporterPayload(canExport));
        }

        return InteractionResultHolder.sidedSuccess(
                player.getItemInHand(hand),
                level.isClientSide()
        );
    }
}