package dev.dungeonbuilder.selection;

import dev.dungeonbuilder.item.ModItems;
import dev.dungeonbuilder.network.ModNetworking.SyncSelectionPayload;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Gestiona las selecciones realizadas exclusivamente con el Seleccionador de estructuras. */
public final class SelectionManager {
    private static final Map<UUID, SelectionData> SELECTIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_CLICK_MS = new ConcurrentHashMap<>();
    private static final long CLICK_COOLDOWN_MS = 220L;

    private SelectionManager() {}

    public static void register() {
        // Red de seguridad para nunca destruir un bloque mientras se sostiene el Seleccionador.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
                !isHoldingSelector(player));

        // Limpia memoria al desconectarse el jugador.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            SELECTIONS.remove(id);
            LAST_CLICK_MS.remove(id);
        });
    }

    public static SelectionData getSelection(UUID playerId) {
        return SELECTIONS.get(playerId);
    }

    public static boolean hasFirstCornerIn(UUID playerId, ResourceKey<Level> dimension) {
        SelectionData data = SELECTIONS.get(playerId);
        return data != null && data.dimension().equals(dimension) && !data.isComplete();
    }

    public static boolean hasCompleteSelectionIn(UUID playerId, ResourceKey<Level> dimension) {
        SelectionData data = SELECTIONS.get(playerId);
        return data != null && data.dimension().equals(dimension) && data.isComplete();
    }

    public static void clearSelection(UUID playerId) {
        SELECTIONS.remove(playerId);
    }

    public static void syncToClient(ServerPlayer player) {
        SelectionData data = SELECTIONS.get(player.getUUID());
        if (data == null) {
            ServerPlayNetworking.send(player, new SyncSelectionPayload(
                    false, "", BlockPos.ZERO, false, BlockPos.ZERO));
            return;
        }
        ServerPlayNetworking.send(player, new SyncSelectionPayload(
                true,
                data.dimension().location().toString(),
                data.firstCorner(),
                data.isComplete(),
                data.isComplete() ? data.secondCorner() : BlockPos.ZERO
        ));
    }

    public static void handleMarkCorner(ServerPlayer player, BlockPos pos) {
        if (player.isSpectator() || !isHoldingSelector(player)) {
            return;
        }
        if (player.blockPosition().distSqr(pos) > 144) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = LAST_CLICK_MS.get(player.getUUID());
        if (last != null && (now - last) < CLICK_COOLDOWN_MS) {
            return;
        }
        LAST_CLICK_MS.put(player.getUUID(), now);

        ServerLevel world = player.serverLevel();

        if (player.isShiftKeyDown()) {
            SELECTIONS.remove(player.getUUID());
            syncToClient(player);
            player.displayClientMessage(prefix(ChatFormatting.YELLOW)
                    .append(Component.translatable("message.dungeonbuilder.selection_cancelled")
                            .withStyle(ChatFormatting.WHITE)), true);
            return;
        }

        SelectionData current = SELECTIONS.get(player.getUUID());
        if (current != null && !current.dimension().equals(world.dimension())) {
            player.displayClientMessage(prefix(ChatFormatting.YELLOW)
                    .append(Component.translatable("message.dungeonbuilder.selection_new_dimension")
                            .withStyle(ChatFormatting.GRAY)), false);
            current = null;
        }

        if (current == null || current.isComplete()) {
            SELECTIONS.put(player.getUUID(), new SelectionData(world.dimension(), pos));
            syncToClient(player);
            player.displayClientMessage(prefix(ChatFormatting.YELLOW)
                    .append(Component.translatable("message.dungeonbuilder.selection_first_corner",
                                    Component.literal(formatPos(pos)).withStyle(ChatFormatting.AQUA))
                            .withStyle(ChatFormatting.WHITE)), true);
            return;
        }

        current.setSecondCorner(pos);
        syncToClient(player);

        BlockPos min = current.min();
        BlockPos max = current.max();

        player.displayClientMessage(prefix(ChatFormatting.GREEN)
                .append(Component.translatable("message.dungeonbuilder.selection_complete")
                        .withStyle(ChatFormatting.WHITE)), false);
        player.displayClientMessage(Component.translatable("message.dungeonbuilder.selection_bounds",
                        Component.literal(formatPos(min)).withStyle(ChatFormatting.AQUA),
                        Component.literal(formatPos(max)).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY), false);
        player.displayClientMessage(Component.translatable("message.dungeonbuilder.selection_dimensions",
                        Component.literal(String.valueOf(current.width())).withStyle(ChatFormatting.YELLOW),
                        Component.literal(String.valueOf(current.height())).withStyle(ChatFormatting.YELLOW),
                        Component.literal(String.valueOf(current.depth())).withStyle(ChatFormatting.YELLOW))
                .withStyle(ChatFormatting.GRAY), false);
        player.displayClientMessage(Component.translatable("message.dungeonbuilder.selection_save_hint",
                        Component.literal(String.valueOf(current.volume())).withStyle(ChatFormatting.YELLOW))
                .withStyle(ChatFormatting.WHITE), true);
    }

    public static boolean isHoldingSelector(Player player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return main.is(ModItems.STRUCTURE_SELECTOR) || off.is(ModItems.STRUCTURE_SELECTOR);
    }

    private static net.minecraft.network.chat.MutableComponent prefix(ChatFormatting color) {
        return Component.literal("DungeonBuilder ").withStyle(color)
                .append(Component.literal("» ").withStyle(ChatFormatting.GRAY));
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}