package dev.dungeonbuilder.export;

import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.network.ModNetworking.ExportChunkPayload;
import dev.dungeonbuilder.network.ModNetworking.ExportResultPayload;
import dev.dungeonbuilder.permissions.PermissionsManager;
import dev.dungeonbuilder.serverlibrary.ServerLibrary;
import dev.dungeonbuilder.structure.DungeonStructure;
import dev.dungeonbuilder.structure.StructureCapture;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Gestiona la recepción, validación y guardado de estructuras exportadas al servidor. */
public final class ExporterManager {
    private static final Map<UUID, PendingExport> PENDING_EXPORTS = new ConcurrentHashMap<>();

    private ExporterManager() {}

    private static final class PendingExport {
        final UUID playerId;
        final String category;
        final int totalChunks;
        final byte[][] chunks;
        int received = 0;

        PendingExport(UUID playerId, String category, int totalChunks) {
            this.playerId = playerId;
            this.category = category;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }
    }

    public static boolean canExport(ServerPlayer player) {
        return PermissionsManager.canExport(player);
    }

    public static void clearPlayer(UUID playerId) {
        PENDING_EXPORTS.values().removeIf(pending -> pending.playerId.equals(playerId));
    }

    public static void handleExportChunk(ServerPlayer player, ExportChunkPayload payload) {
        if (!canExport(player)) {
            PENDING_EXPORTS.remove(payload.transferId());
            ServerPlayNetworking.send(player, new ExportResultPayload(
                    false, "No tienes permisos en este servidor para exportar estructuras.", ""));
            return;
        }

        if (payload.totalChunks() <= 0 || payload.totalChunks() > 1024
                || payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.totalChunks()) {
            PENDING_EXPORTS.remove(payload.transferId());
            ServerPlayNetworking.send(player, new ExportResultPayload(
                    false, "Paquete de exportación inválido.", ""));
            return;
        }

        PendingExport pending = PENDING_EXPORTS.computeIfAbsent(payload.transferId(),
                id -> new PendingExport(player.getUUID(), payload.category(), payload.totalChunks()));

        if (!pending.playerId.equals(player.getUUID()) || pending.totalChunks != payload.totalChunks()) {
            PENDING_EXPORTS.remove(payload.transferId());
            ServerPlayNetworking.send(player, new ExportResultPayload(
                    false, "Error de validación en la transferencia.", ""));
            return;
        }

        if (pending.chunks[payload.chunkIndex()] == null) {
            pending.chunks[payload.chunkIndex()] = payload.data();
            pending.received++;
        }

        if (pending.received < pending.totalChunks) {
            return;
        }

        PENDING_EXPORTS.remove(payload.transferId());

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (byte[] chunk : pending.chunks) {
                if (chunk == null) {
                    throw new IllegalStateException("Falta un fragmento de la estructura");
                }
                baos.write(chunk);
                if (baos.size() > StructureCapture.MAX_COMPRESSED_BYTES) {
                    throw new IllegalArgumentException("La estructura supera el límite máximo (16 MB).");
                }
            }

            byte[] compressedBytes = baos.toByteArray();
            DungeonStructure structure = DungeonStructure.fromCompressedBytes(
                    compressedBytes,
                    player.serverLevel().holderLookup(Registries.BLOCK)
            );

            List<String> missingMods = findMissingServerMods(structure.requiredMods());
            if (!missingMods.isEmpty()) {
                ServerPlayNetworking.send(player, new ExportResultPayload(
                        false,
                        "Faltan mods en el servidor: " + String.join(", ", missingMods),
                        ""
                ));
                return;
            }

            ServerLibrary.Entry saved = ServerLibrary.saveExportedStructure(
                    player, structure, compressedBytes, pending.category);

            DungeonBuilder.LOGGER.info("Jugador {} exportó '{}' ({}.dungeon) al servidor.",
                    player.getScoreboardName(), saved.name, saved.id);

            ServerPlayNetworking.send(player, new ExportResultPayload(
                    true,
                    "¡Exportada! '" + saved.name + "' (" + saved.id + ".dungeon)",
                    saved.id
            ));
        } catch (IllegalArgumentException e) {
            ServerPlayNetworking.send(player, new ExportResultPayload(
                    false,
                    e.getMessage() == null ? "La estructura no es válida." : e.getMessage(),
                    ""
            ));
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error exportando estructura de {}", player.getScoreboardName(), e);
            ServerPlayNetworking.send(player, new ExportResultPayload(
                    false, "Ocurrió un error en el servidor al guardar la estructura.", ""
            ));
        }
    }

    private static List<String> findMissingServerMods(List<String> requiredMods) {
        if (requiredMods == null || requiredMods.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        FabricLoader loader = FabricLoader.getInstance();
        for (String modId : requiredMods) {
            if (!"minecraft".equals(modId) && !loader.isModLoaded(modId)) {
                missing.add(modId);
            }
        }
        return missing;
    }
}