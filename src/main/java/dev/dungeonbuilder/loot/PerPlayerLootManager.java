package dev.dungeonbuilder.loot;

import dev.dungeonbuilder.loot.LootModels.LootMode;
import dev.dungeonbuilder.loot.LootModels.LootProfile;
import dev.dungeonbuilder.loot.PerPlayerLootSavedData.ChestRecord;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Intercepta la apertura de cofres configurados como "Loot por jugador" y entrega un inventario separado por UUID. */
public final class PerPlayerLootManager {
    private PerPlayerLootManager() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player.isSpectator() || world.isClientSide() || !(world instanceof ServerLevel serverLevel)
                    || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            BlockState state = serverLevel.getBlockState(pos);
            PerPlayerLootSavedData data = getData(serverLevel);

            BlockPos otherHalf = getConnectedChestHalf(pos, state);
            boolean hasPrimary = data.hasChest(pos);
            boolean hasOther = otherHalf != null && data.hasChest(otherHalf);
            if (!hasPrimary && !hasOther) {
                return InteractionResult.PASS;
            }

            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.CONSUME;
            }

            openPersonalChest(serverLevel, serverPlayer, data, pos, state, otherHalf);
            return InteractionResult.SUCCESS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && world instanceof ServerLevel serverLevel) {
                getData(serverLevel).removeChest(pos);
            }
        });
    }

    public static PerPlayerLootSavedData getData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                PerPlayerLootSavedData.FACTORY,
                PerPlayerLootSavedData.DATA_NAME
        );
    }

    public static void registerChest(
            ServerLevel level,
            BlockPos pos,
            LootMode mode,
            String profileId,
            LootProfile embeddedProfile,
            List<CompoundTag> originalItems
    ) {
        ChestRecord rec = new ChestRecord();
        rec.mode = mode;
        rec.profileId = profileId == null ? "" : profileId;
        rec.embeddedProfile = embeddedProfile != null ? embeddedProfile.copy() : null;
        rec.originalItems = originalItems == null ? new ArrayList<>() : new ArrayList<>(originalItems);
        getData(level).putChest(pos.immutable(), rec);
    }

    public static void removeChest(ServerLevel level, BlockPos pos) {
        if (level != null && pos != null) {
            getData(level).removeChest(pos);
        }
    }

    private static BlockPos getConnectedChestHalf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE) {
                return pos.relative(ChestBlock.getConnectedDirection(state));
            }
        }
        return null;
    }

    private static void openPersonalChest(
            ServerLevel level,
            ServerPlayer player,
            PerPlayerLootSavedData data,
            BlockPos clickedPos,
            BlockState state,
            BlockPos otherHalf
    ) {
        boolean isDouble = otherHalf != null;
        BlockPos keyPos;
        if (isDouble) {
            if (data.hasChest(clickedPos) && data.hasChest(otherHalf)) {
                keyPos = clickedPos.compareTo(otherHalf) <= 0 ? clickedPos : otherHalf;
            } else {
                keyPos = data.hasChest(clickedPos) ? clickedPos : otherHalf;
            }
        } else {
            keyPos = clickedPos;
        }

        ChestRecord rec = data.getChest(keyPos);
        if (rec == null) {
            return;
        }

        int slotCount = isDouble ? 54 : 27;
        HolderLookup.Provider registries = level.registryAccess();
        UUID playerId = player.getUUID();

        SimpleContainer container = new SimpleContainer(slotCount) {
            @Override
            public void setChanged() {
                super.setChanged();
                rec.playerInventories.put(playerId, PerPlayerLootSavedData.serializeContainer(this, registries));
                data.setDirty();
            }
        };

        List<CompoundTag> existing = rec.playerInventories.get(playerId);
        if (existing != null) {
            loadItemsIntoContainer(container, existing, registries);
        } else {
            if (rec.mode == LootMode.RANDOM_LOOT) {
                LootProfile profile = LootProfileStorage.getProfileOrFallback(rec.profileId);
                if (rec.embeddedProfile != null) {
                    profile = rec.embeddedProfile;
                }
                if (profile != null) {
                    LootProfileStorage.generateLootIntoContainer(container, profile, level.random, registries);
                }
            } else {
                // ORIGINAL: restaura ítems originales (combinando ambas mitades si es cofre doble)
                if (isDouble && data.hasChest(clickedPos) && data.hasChest(otherHalf) && !clickedPos.equals(otherHalf)) {
                    BlockPos secondPos = keyPos.equals(clickedPos) ? otherHalf : clickedPos;
                    ChestRecord secondRec = data.getChest(secondPos);
                    loadItemsIntoContainer(container, rec.originalItems, registries, 0);
                    if (secondRec != null) {
                        loadItemsIntoContainer(container, secondRec.originalItems, registries, 27);
                    }
                } else {
                    loadItemsIntoContainer(container, rec.originalItems, registries);
                }
            }
            rec.playerInventories.put(playerId, PerPlayerLootSavedData.serializeContainer(container, registries));
            data.setDirty();
        }

        if (state.getBlock() instanceof BarrelBlock) {
            level.playSound(null, clickedPos, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS,
                    0.5F, level.random.nextFloat() * 0.1F + 0.9F);
        } else {
            level.playSound(null, clickedPos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS,
                    0.5F, level.random.nextFloat() * 0.1F + 0.9F);
        }

        Component title = state.getBlock().getName().copy().append(" (Tu loot)");
        player.openMenu(new SimpleMenuProvider(
                (syncId, inv, p) -> slotCount == 54
                        ? ChestMenu.sixRows(syncId, inv, container)
                        : ChestMenu.threeRows(syncId, inv, container),
                title
        ));
    }

    private static void loadItemsIntoContainer(
            SimpleContainer container,
            List<CompoundTag> itemTags,
            HolderLookup.Provider registries
    ) {
        loadItemsIntoContainer(container, itemTags, registries, 0);
    }

    private static void loadItemsIntoContainer(
            SimpleContainer container,
            List<CompoundTag> itemTags,
            HolderLookup.Provider registries,
            int slotOffset
    ) {
        if (itemTags == null) {
            return;
        }
        for (CompoundTag tag : itemTags) {
            int slot = (tag.getByte("Slot") & 255) + slotOffset;
            if (slot >= 0 && slot < container.getContainerSize()) {
                ItemStack stack = ItemStack.parseOptional(registries, tag);
                if (!stack.isEmpty()) {
                    container.setItem(slot, stack);
                }
            }
        }
    }
}