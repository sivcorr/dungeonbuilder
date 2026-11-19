package dev.dungeonbuilder.placement;

import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.loot.LootModels.ChestLootConfig;
import dev.dungeonbuilder.loot.LootModels.LootMode;
import dev.dungeonbuilder.loot.LootModels.LootProfile;
import dev.dungeonbuilder.loot.LootProfileStorage;
import dev.dungeonbuilder.loot.PerPlayerLootManager;
import dev.dungeonbuilder.loot.PerPlayerLootSavedData;
import dev.dungeonbuilder.network.ModNetworking.UndoStatePayload;
import dev.dungeonbuilder.permissions.PermissionsManager;
import dev.dungeonbuilder.structure.DungeonStructure;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Coloca y deshace estructuras en el servidor por lotes por tick para evitar lagazos. */
public final class PlacementManager {
    private static final int PLACED_BLOCKS_PER_TICK = 4096;
    private static final int MAX_SCANNED_PER_TICK = 32_768;
    private static final int MAX_UNDO_BLOCKS = 500_000;

    private static final Map<UUID, TickJob> ACTIVE_JOBS = new ConcurrentHashMap<>();
    private static final Map<UUID, UndoRecord> LAST_UNDO = new ConcurrentHashMap<>();

    private PlacementManager() {}

    private record UndoBlock(BlockPos pos, BlockState oldState, CompoundTag oldBeTag) {}

    private record UndoRecord(ResourceKey<Level> dimension, String structureName, List<UndoBlock> blocks) {}

    private interface TickJob {
        UUID playerId();
        String label();
        int progressPercent();
        boolean step(int placeBudget, int scanBudget);
        void onComplete(MinecraftServer server, ServerPlayer player);
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PlacementManager::onServerTick);
    }

    public static void clearPlayer(UUID playerId) {
        ACTIVE_JOBS.remove(playerId);
        LAST_UNDO.remove(playerId);
    }

    public static boolean hasActiveJob(UUID playerId) {
        return ACTIVE_JOBS.containsKey(playerId);
    }

    public static void syncUndoState(ServerPlayer player) {
        UndoRecord rec = LAST_UNDO.get(player.getUUID());
        ServerPlayNetworking.send(player, new UndoStatePayload(
                rec != null,
                rec != null ? rec.structureName() : ""
        ));
    }

    public static void enqueue(ServerPlayer player, ServerLevel level, BlockPos origin,
                               Rotation rotation, DungeonStructure structure) {
        ACTIVE_JOBS.put(player.getUUID(),
                new PlacementJob(player.getUUID(), level, origin.immutable(), rotation, structure));
    }

    public static boolean enqueueUndo(ServerPlayer player) {
        if (hasActiveJob(player.getUUID())) {
            player.displayClientMessage(
                    Component.translatable("message.dungeonbuilder.error_place_busy").withStyle(ChatFormatting.RED),
                    false
            );
            return false;
        }
        if (!PermissionsManager.isAdmin(player)
                && !PermissionsManager.canPlaceServer(player)
                && !PermissionsManager.canPlaceLocal(player)) {
            player.displayClientMessage(
                    Component.literal("No tienes permisos para deshacer colocaciones.").withStyle(ChatFormatting.RED),
                    false
            );
            return false;
        }

        UndoRecord undo = LAST_UNDO.get(player.getUUID());
        if (undo == null) {
            syncUndoState(player);
            player.displayClientMessage(
                    Component.literal("No hay ninguna colocación reciente para deshacer.").withStyle(ChatFormatting.YELLOW),
                    false
            );
            return false;
        }

        ServerLevel targetLevel = player.server.getLevel(undo.dimension());
        if (targetLevel == null || !player.serverLevel().dimension().equals(undo.dimension())) {
            player.displayClientMessage(
                    Component.literal("Debes estar en la misma dimensión donde colocaste '" + undo.structureName() + "' para deshacerla.")
                            .withStyle(ChatFormatting.RED),
                    false
            );
            return false;
        }

        LAST_UNDO.remove(player.getUUID());
        syncUndoState(player);
        ACTIVE_JOBS.put(player.getUUID(), new UndoJob(player.getUUID(), targetLevel, undo));
        player.displayClientMessage(
                Component.literal("DungeonBuilder ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("Deshaciendo '" + undo.structureName() + "'...").withStyle(ChatFormatting.WHITE)),
                true
        );
        return true;
    }

    private static void onServerTick(MinecraftServer server) {
        if (ACTIVE_JOBS.isEmpty()) {
            return;
        }
        boolean showProgress = server.getTickCount() % 10 == 0;
        Iterator<Map.Entry<UUID, TickJob>> it = ACTIVE_JOBS.entrySet().iterator();
        while (it.hasNext()) {
            TickJob job = it.next().getValue();
            try {
                boolean finished = job.step(PLACED_BLOCKS_PER_TICK, MAX_SCANNED_PER_TICK);
                ServerPlayer player = server.getPlayerList().getPlayer(job.playerId());

                if (finished) {
                    it.remove();
                    job.onComplete(server, player);
                } else if (showProgress && player != null) {
                    int pct = job.progressPercent();
                    player.displayClientMessage(
                            Component.literal("DungeonBuilder ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(job.label() + ": ").withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal(pct + "%").withStyle(ChatFormatting.YELLOW)),
                            true
                    );
                }
            } catch (Exception e) {
                DungeonBuilder.LOGGER.error("Error procesando trabajo '{}'", job.label(), e);
                ServerPlayer player = server.getPlayerList().getPlayer(job.playerId());
                if (player != null) {
                    player.displayClientMessage(
                            Component.translatable("message.dungeonbuilder.error_place_failed")
                                    .withStyle(ChatFormatting.RED),
                            false
                    );
                }
                it.remove();
            }
        }
    }

    private static final class PlacementJob implements TickJob {
        final UUID playerId;
        final ServerLevel level;
        final BlockPos origin;
        final Rotation rotation;
        final DungeonStructure structure;

        private final int width;
        private final int height;
        private final int depth;
        private final int totalBlocks;

        private final boolean canRecordUndo;
        private final List<UndoBlock> undoBlocks;
        private boolean undoOverflowed = false;

        private int blockCursor = 0;
        private int beCursor = 0;

        PlacementJob(UUID playerId, ServerLevel level, BlockPos origin, Rotation rotation, DungeonStructure structure) {
            this.playerId = playerId;
            this.level = level;
            this.origin = origin;
            this.rotation = rotation;
            this.structure = structure;
            this.width = structure.width();
            this.height = structure.height();
            this.depth = structure.depth();
            this.totalBlocks = width * height * depth;

            long vol = (long) width * height * depth;
            this.canRecordUndo = vol <= MAX_UNDO_BLOCKS;
            this.undoBlocks = this.canRecordUndo ? new ArrayList<>((int) Math.min(vol, 16_384)) : List.of();
        }

        @Override
        public UUID playerId() {
            return playerId;
        }

        @Override
        public String label() {
            return structure.name();
        }

        @Override
        public int progressPercent() {
            int totalWork = Math.max(1, totalBlocks + structure.blockEntities().size());
            int done = blockCursor + beCursor;
            return Math.min(99, (int) ((done * 100L) / totalWork));
        }

        @Override
        public boolean step(int placeBudget, int scanBudget) {
            int placed = 0;
            int scanned = 0;

            while (blockCursor < totalBlocks && placed < placeBudget && scanned < scanBudget) {
                int y = blockCursor / (width * depth);
                int rem = blockCursor % (width * depth);
                int z = rem / width;
                int x = rem % width;

                BlockPos rel = structure.rotateLocalPos(x, y, z, rotation);
                BlockPos target = origin.offset(rel);
                if (level.isInWorldBounds(target)) {
                    BlockState newState = structure.getRotatedBlockState(x, y, z, rotation);
                    BlockState oldState = level.getBlockState(target);
                    if (!(newState.isAir() && oldState.isAir())) {
                        if (canRecordUndo && !undoOverflowed) {
                            if (undoBlocks.size() < MAX_UNDO_BLOCKS) {
                                CompoundTag oldBeTag = null;
                                BlockEntity oldBe = level.getBlockEntity(target);
                                if (oldBe != null) {
                                    oldBeTag = oldBe.saveWithFullMetadata(level.registryAccess());
                                }
                                undoBlocks.add(new UndoBlock(target.immutable(), oldState, oldBeTag));
                            } else {
                                undoOverflowed = true;
                                undoBlocks.clear();
                            }
                        }

                        PerPlayerLootManager.removeChest(level, target);
                        level.setBlock(target, newState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        placed++;
                    }
                }

                blockCursor++;
                scanned++;
            }

            if (blockCursor < totalBlocks) {
                return false;
            }

            var blockEntities = structure.blockEntities();
            while (beCursor < blockEntities.size() && placed < placeBudget) {
                DungeonStructure.BlockEntityRecord rec = blockEntities.get(beCursor++);
                BlockPos rel = structure.rotateLocalPos(rec.x(), rec.y(), rec.z(), rotation);
                BlockPos target = origin.offset(rel);
                if (level.isInWorldBounds(target)) {
                    BlockEntity be = level.getBlockEntity(target);
                    if (be != null) {
                        ChestLootConfig lootCfg = structure.getChestLootConfig(rec.x(), rec.y(), rec.z());
                        boolean perPlayer = lootCfg != null && lootCfg.perPlayer();
                        boolean applyRandomLoot = lootCfg != null && lootCfg.mode() == LootMode.RANDOM_LOOT;

                        CompoundTag tag = rec.data().copy();
                        tag.putInt("x", target.getX());
                        tag.putInt("y", target.getY());
                        tag.putInt("z", target.getZ());

                        if (perPlayer) {
                            tag.remove("Items");
                            tag.remove("LootTable");
                            tag.remove("LootTableSeed");
                            be.loadWithComponents(tag, level.registryAccess());
                            if (be instanceof Container c) {
                                c.clearContent();
                            }
                            if (be instanceof RandomizableContainer rc) {
                                rc.setLootTable(null, 0L);
                            }

                            LootProfile resolved = structure.resolveLootProfile(lootCfg.profileId());
                            if (resolved == null && applyRandomLoot) {
                                resolved = LootProfileStorage.getProfileOrFallback(lootCfg.profileId());
                            }
                            List<CompoundTag> origItems = PerPlayerLootSavedData.extractItemTagsFromBeNbt(rec.data());
                            PerPlayerLootManager.registerChest(
                                    level,
                                    target,
                                    lootCfg.mode(),
                                    lootCfg.profileId(),
                                    resolved,
                                    origItems
                            );
                        } else {
                            if (applyRandomLoot) {
                                tag.remove("LootTable");
                                tag.remove("LootTableSeed");
                            }

                            be.loadWithComponents(tag, level.registryAccess());

                            if (applyRandomLoot && be instanceof Container container) {
                                if (be instanceof RandomizableContainer rc) {
                                    rc.setLootTable(null, 0L);
                                }
                                LootProfile profile = structure.resolveLootProfile(lootCfg.profileId());
                                if (profile == null) {
                                    profile = LootProfileStorage.getProfileOrFallback(lootCfg.profileId());
                                }
                                if (profile != null) {
                                    LootProfileStorage.generateLootIntoContainer(
                                            container, profile, level.random, level.registryAccess());
                                }
                            }
                        }

                        be.setChanged();
                        BlockState placedState = level.getBlockState(target);
                        level.sendBlockUpdated(target, placedState, placedState, Block.UPDATE_ALL);
                    }
                }
                placed++;
            }

            return beCursor >= blockEntities.size();
        }

        @Override
        public void onComplete(MinecraftServer server, ServerPlayer player) {
            if (canRecordUndo && !undoOverflowed && !undoBlocks.isEmpty()) {
                LAST_UNDO.put(playerId, new UndoRecord(level.dimension(), structure.name(), undoBlocks));
            } else {
                LAST_UNDO.remove(playerId);
            }

            if (player != null) {
                syncUndoState(player);
                player.displayClientMessage(
                        Component.literal("DungeonBuilder ").withStyle(ChatFormatting.GREEN)
                                .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                                .append(Component.translatable("message.dungeonbuilder.place_success",
                                                Component.literal(structure.name()).withStyle(ChatFormatting.AQUA),
                                                Component.literal(origin.getX() + ", " + origin.getY() + ", " + origin.getZ())
                                                        .withStyle(ChatFormatting.YELLOW))
                                        .withStyle(ChatFormatting.WHITE)),
                        false
                );
            }
        }
    }

    private static final class UndoJob implements TickJob {
        private final UUID playerId;
        private final ServerLevel level;
        private final UndoRecord record;
        private final List<UndoBlock> beRestores = new ArrayList<>();
        private int cursor;
        private int beCursor = 0;

        UndoJob(UUID playerId, ServerLevel level, UndoRecord record) {
            this.playerId = playerId;
            this.level = level;
            this.record = record;
            this.cursor = record.blocks().size() - 1;
        }

        @Override
        public UUID playerId() {
            return playerId;
        }

        @Override
        public String label() {
            return "Deshacer " + record.structureName();
        }

        @Override
        public int progressPercent() {
            int total = Math.max(1, record.blocks().size());
            int done = (record.blocks().size() - 1 - cursor);
            return Math.min(99, (int) ((done * 100L) / total));
        }

        @Override
        public boolean step(int placeBudget, int scanBudget) {
            int restored = 0;
            while (cursor >= 0 && restored < placeBudget) {
                UndoBlock u = record.blocks().get(cursor--);
                if (level.isInWorldBounds(u.pos())) {
                    PerPlayerLootManager.removeChest(level, u.pos());
                    level.setBlock(u.pos(), u.oldState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    if (u.oldBeTag() != null) {
                        beRestores.add(u);
                    }
                    restored++;
                }
            }

            if (cursor >= 0) {
                return false;
            }

            while (beCursor < beRestores.size() && restored < placeBudget) {
                UndoBlock u = beRestores.get(beCursor++);
                BlockEntity be = level.getBlockEntity(u.pos());
                if (be != null) {
                    CompoundTag tag = u.oldBeTag().copy();
                    tag.putInt("x", u.pos().getX());
                    tag.putInt("y", u.pos().getY());
                    tag.putInt("z", u.pos().getZ());
                    be.loadWithComponents(tag, level.registryAccess());
                    be.setChanged();
                    BlockState st = level.getBlockState(u.pos());
                    level.sendBlockUpdated(u.pos(), st, st, Block.UPDATE_ALL);
                }
                restored++;
            }

            return beCursor >= beRestores.size();
        }

        @Override
        public void onComplete(MinecraftServer server, ServerPlayer player) {
            if (player != null) {
                syncUndoState(player);
                player.displayClientMessage(
                        Component.literal("DungeonBuilder ").withStyle(ChatFormatting.GREEN)
                                .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal("Se deshizo la colocación de '")
                                        .append(Component.literal(record.structureName()).withStyle(ChatFormatting.AQUA))
                                        .append(Component.literal("'."))
                                        .withStyle(ChatFormatting.WHITE)),
                        false
                );
            }
        }
    }
}