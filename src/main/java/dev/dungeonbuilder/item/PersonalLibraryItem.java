package dev.dungeonbuilder.item;

import dev.dungeonbuilder.network.ModNetworking.OpenPersonalLibraryPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Abre la biblioteca personal local ("Estructuras mías") del jugador. */
public class PersonalLibraryItem extends Item {
    public PersonalLibraryItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new OpenPersonalLibraryPayload());
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
}