package dev.dungeonbuilder;

import dev.dungeonbuilder.gui.ChestLootConfigScreen;
import dev.dungeonbuilder.gui.ExporterScreen;
import dev.dungeonbuilder.gui.PermissionsScreen;
import dev.dungeonbuilder.gui.PersonalLibraryScreen;
import dev.dungeonbuilder.gui.SelectorSaveScreen;
import dev.dungeonbuilder.gui.ServerLibraryScreen;
import dev.dungeonbuilder.network.ModNetworking.ExportResultPayload;
import dev.dungeonbuilder.network.ModNetworking.MarkCornerPayload;
import dev.dungeonbuilder.network.ModNetworking.OpenExporterPayload;
import dev.dungeonbuilder.network.ModNetworking.OpenPersonalLibraryPayload;
import dev.dungeonbuilder.network.ModNetworking.OpenSelectorSavePayload;
import dev.dungeonbuilder.network.ModNetworking.OpenServerChestConfigPayload;
import dev.dungeonbuilder.network.ModNetworking.OpenServerLibraryPayload;
import dev.dungeonbuilder.network.ModNetworking.PermissionsDataPayload;
import dev.dungeonbuilder.network.ModNetworking.PermissionsSaveResultPayload;
import dev.dungeonbuilder.network.ModNetworking.PlaceResultPayload;
import dev.dungeonbuilder.network.ModNetworking.SaveStructureChunkPayload;
import dev.dungeonbuilder.network.ModNetworking.SaveStructureResultPayload;
import dev.dungeonbuilder.network.ModNetworking.ServerChestSaveResultPayload;
import dev.dungeonbuilder.network.ModNetworking.ServerLibraryActionResultPayload;
import dev.dungeonbuilder.network.ModNetworking.ServerLibraryListPayload;
import dev.dungeonbuilder.network.ModNetworking.ServerStructureChunkPayload;
import dev.dungeonbuilder.network.ModNetworking.SyncSelectionPayload;
import dev.dungeonbuilder.network.ModNetworking.UndoStatePayload;
import dev.dungeonbuilder.placement.ClientUndoState;
import dev.dungeonbuilder.preview.PreviewManager;
import dev.dungeonbuilder.selection.SelectionManager;
import dev.dungeonbuilder.selection.SelectionRenderer;
import dev.dungeonbuilder.storage.LocalLibrary;
import dev.dungeonbuilder.storage.ThumbnailGenerator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DungeonBuilderClient implements ClientModInitializer {
    private static long lastSelectClickMs = 0L;
    private static final Map<UUID, PendingSave> PENDING_SAVES = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingServerDownload> PENDING_SERVER_DOWNLOADS = new ConcurrentHashMap<>();

    private static final class PendingSave {
        final String name;
        final byte[][] chunks;
        int received = 0;

        PendingSave(String name, int totalChunks) {
            this.name = name;
            this.chunks = new byte[totalChunks][];
        }
    }

    private static final class PendingServerDownload {
        final String structureId;
        final boolean startPreview;
        final byte[][] chunks;
        int received = 0;

        PendingServerDownload(String structureId, boolean startPreview, int totalChunks) {
            this.structureId = structureId;
            this.startPreview = startPreview;
            this.chunks = new byte[totalChunks][];
        }
    }

    @Override
    public void onInitializeClient() {
        PreviewManager.register();
        SelectionRenderer.register();

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() || player.isSpectator() || !SelectionManager.isHoldingSelector(player)) {
                return InteractionResult.PASS;
            }
            long now = System.currentTimeMillis();
            if (now - lastSelectClickMs >= 220L) {
                lastSelectClickMs = now;
                ClientPlayNetworking.send(new MarkCornerPayload(pos));
            }
            return InteractionResult.FAIL;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PENDING_SAVES.clear();
            PENDING_SERVER_DOWNLOADS.clear();
            PreviewManager.clear();
            PreviewManager.clearServerCache();
            ThumbnailGenerator.clearServerCache();
            SelectionRenderer.clear();
            ClientUndoState.clear();
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncSelectionPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    SelectionRenderer.update(
                            payload.active(),
                            payload.dimensionId(),
                            payload.firstCorner(),
                            payload.hasSecondCorner(),
                            payload.secondCorner()
                    );
                    if (payload.active()) {
                        float pitch = payload.hasSecondCorner() ? 1.35F : 1.0F;
                        context.client().getSoundManager().play(
                                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(UndoStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    ClientUndoState.set(payload.available(), payload.structureName());
                    if (context.client().screen instanceof ServerLibraryScreen serverScreen) {
                        serverScreen.refreshUndoButton();
                    } else if (context.client().screen instanceof PersonalLibraryScreen personalScreen) {
                        personalScreen.refreshUndoButton();
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(OpenSelectorSavePayload.TYPE, (payload, context) ->
                context.client().execute(() -> context.client().setScreen(new SelectorSaveScreen(
                        payload.min(), payload.max(), payload.width(), payload.height(), payload.depth()
                ))));

        ClientPlayNetworking.registerGlobalReceiver(OpenPersonalLibraryPayload.TYPE, (payload, context) ->
                context.client().execute(() -> context.client().setScreen(new PersonalLibraryScreen())));

        ClientPlayNetworking.registerGlobalReceiver(OpenExporterPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        context.client().setScreen(new ExporterScreen(payload.canExport()))
                ));

        ClientPlayNetworking.registerGlobalReceiver(OpenServerLibraryPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        context.client().setScreen(new ServerLibraryScreen(
                                payload.canPreview(),
                                payload.canPlace(),
                                payload.canDelete(),
                                payload.canEditServerLoot(),
                                payload.roleName(),
                                payload.entries()
                        ))
                ));

        ClientPlayNetworking.registerGlobalReceiver(ServerLibraryListPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    Minecraft mc = context.client();
                    if (mc.screen instanceof ServerLibraryScreen serverScreen) {
                        if (!payload.canView()) {
                            mc.setScreen(null);
                            if (mc.player != null) {
                                mc.player.displayClientMessage(
                                        Component.literal("DungeonBuilder » Ya no tienes permisos para ver la biblioteca del servidor.")
                                                .withStyle(ChatFormatting.RED),
                                        false
                                );
                            }
                            return;
                        }
                        serverScreen.updateFromServer(
                                payload.canPreview(),
                                payload.canPlace(),
                                payload.canDelete(),
                                payload.canEditServerLoot(),
                                payload.roleName(),
                                payload.entries()
                        );
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(OpenServerChestConfigPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    Minecraft mc = context.client();
                    Screen parent = mc.screen;
                    mc.setScreen(new ChestLootConfigScreen(parent, payload));
                }));

        ClientPlayNetworking.registerGlobalReceiver(ServerChestSaveResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().screen instanceof ChestLootConfigScreen chestScreen) {
                        chestScreen.handleServerSaveResult(payload.success(), payload.message());
                    } else if (context.client().player != null) {
                        context.client().player.displayClientMessage(
                                Component.literal(payload.message())
                                        .withStyle(payload.success() ? ChatFormatting.GREEN : ChatFormatting.RED),
                                false
                        );
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(PermissionsDataPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    Minecraft mc = context.client();
                    if (mc.screen instanceof PermissionsScreen permScreen) {
                        permScreen.applySnapshot(payload.snapshot());
                    } else {
                        Screen parent = mc.screen;
                        mc.setScreen(new PermissionsScreen(parent, payload.snapshot()));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(PermissionsSaveResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().screen instanceof PermissionsScreen permScreen) {
                        permScreen.handleSaveResult(payload.success(), payload.message());
                    } else if (context.client().player != null) {
                        context.client().player.displayClientMessage(
                                Component.literal(payload.message())
                                        .withStyle(payload.success() ? ChatFormatting.GREEN : ChatFormatting.RED),
                                false
                        );
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(ServerStructureChunkPayload.TYPE, (payload, context) ->
                context.client().execute(() -> handleServerStructureChunk(context.client(), payload)));

        ClientPlayNetworking.registerGlobalReceiver(ServerLibraryActionResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().screen instanceof ServerLibraryScreen serverScreen) {
                        serverScreen.showStatus(payload.success(), payload.message());
                    } else if (context.client().player != null) {
                        context.client().player.displayClientMessage(
                                Component.literal(payload.message())
                                        .withStyle(payload.success() ? ChatFormatting.GREEN : ChatFormatting.RED),
                                false
                        );
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(SaveStructureResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> handleSaveResult(context.client(), payload)));

        ClientPlayNetworking.registerGlobalReceiver(SaveStructureChunkPayload.TYPE, (payload, context) ->
                context.client().execute(() -> handleSaveChunk(context.client(), payload)));

        ClientPlayNetworking.registerGlobalReceiver(PlaceResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> handlePlaceResult(context.client(), payload)));

        ClientPlayNetworking.registerGlobalReceiver(ExportResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> handleExportResult(context.client(), payload)));
    }

    private static void handleServerStructureChunk(Minecraft mc, ServerStructureChunkPayload payload) {
        if (payload.totalChunks() <= 0 || payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.totalChunks()) {
            PENDING_SERVER_DOWNLOADS.remove(payload.transferId());
            if (mc.screen instanceof ServerLibraryScreen serverScreen) {
                serverScreen.showStatus(false, "Error descargando la estructura del servidor.");
            }
            return;
        }

        PendingServerDownload pending = PENDING_SERVER_DOWNLOADS.computeIfAbsent(payload.transferId(),
                id -> new PendingServerDownload(payload.structureId(), payload.startPreview(), payload.totalChunks()));

        if (pending.chunks[payload.chunkIndex()] == null) {
            pending.chunks[payload.chunkIndex()] = payload.data();
            pending.received++;
        }

        if (pending.received < pending.chunks.length) {
            return;
        }

        PENDING_SERVER_DOWNLOADS.remove(payload.transferId());
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (byte[] chunk : pending.chunks) {
                if (chunk == null) {
                    throw new IOException("Fragmento faltante");
                }
                baos.write(chunk);
            }
            byte[] bytes = baos.toByteArray();

            PreviewManager.cacheServerBytes(pending.structureId, bytes);
            var lookup = mc.level != null
                    ? mc.level.holderLookup(Registries.BLOCK)
                    : BuiltInRegistries.BLOCK.asLookup();
            ThumbnailGenerator.getOrCreateServerTexture(pending.structureId, bytes, lookup);

            if (pending.startPreview) {
                if (mc.screen instanceof ServerLibraryScreen) {
                    mc.setScreen(null);
                }
                PreviewManager.startServerPreview(pending.structureId, bytes);
            } else if (mc.screen instanceof ServerLibraryScreen serverScreen) {
                serverScreen.onThumbnailReady(pending.structureId);
            }
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error procesando descarga de estructura del servidor {}",
                    pending.structureId, e);
            if (mc.screen instanceof ServerLibraryScreen serverScreen) {
                serverScreen.showStatus(false, "No se pudo cargar la vista previa.");
            }
        }
    }

    private static void handleExportResult(Minecraft mc, ExportResultPayload payload) {
        if (mc.screen instanceof ExporterScreen exporterScreen) {
            exporterScreen.handleExportResult(payload.success(), payload.message());
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("DungeonBuilder ").withStyle(payload.success() ? ChatFormatting.GREEN : ChatFormatting.RED)
                            .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(payload.message())
                                    .withStyle(payload.success() ? ChatFormatting.WHITE : ChatFormatting.RED)),
                    false
            );
        }
    }

    private static void handleSaveChunk(Minecraft mc, SaveStructureChunkPayload payload) {
        if (payload.totalChunks() <= 0 || payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.totalChunks()) {
            PENDING_SAVES.remove(payload.transferId());
            showSaveError(mc, Component.translatable("message.dungeonbuilder.error_local_save"));
            return;
        }

        PendingSave pending = PENDING_SAVES.computeIfAbsent(payload.transferId(),
                id -> new PendingSave(payload.structureName(), payload.totalChunks()));

        if (pending.chunks[payload.chunkIndex()] == null) {
            pending.chunks[payload.chunkIndex()] = payload.data();
            pending.received++;
        }

        if (pending.received < pending.chunks.length) {
            return;
        }

        PENDING_SAVES.remove(payload.transferId());
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (byte[] chunk : pending.chunks) {
                if (chunk == null) {
                    throw new IOException("Fragmento de guardado faltante");
                }
                baos.write(chunk);
            }
            saveLocalAndNotify(mc, pending.name, baos.toByteArray(), "message.dungeonbuilder.save_success");
        } catch (IOException e) {
            DungeonBuilder.LOGGER.error("Error guardando estructura localmente", e);
            showSaveError(mc, Component.translatable("message.dungeonbuilder.error_local_save"));
        }
    }

    private static void handleSaveResult(Minecraft mc, SaveStructureResultPayload payload) {
        if (payload.success()) {
            try {
                saveLocalAndNotify(mc, payload.arg(), payload.dungeonBytes(), payload.messageKey());
            } catch (IOException e) {
                DungeonBuilder.LOGGER.error("Error guardando estructura localmente", e);
                showSaveError(mc, Component.translatable("message.dungeonbuilder.error_local_save"));
            }
            return;
        }

        Component errorMsg;
        if (payload.arg() != null && payload.arg().contains("|")) {
            String[] parts = payload.arg().split("\\|", 2);
            errorMsg = Component.translatable(payload.messageKey(), parts[0], parts[1]);
        } else if (payload.arg() != null && !payload.arg().isEmpty()) {
            errorMsg = Component.translatable(payload.messageKey(), payload.arg());
        } else {
            errorMsg = Component.translatable(payload.messageKey());
        }
        showSaveError(mc, errorMsg);
    }

    private static void saveLocalAndNotify(Minecraft mc, String name, byte[] dungeonBytes, String messageKey) throws IOException {
        LocalLibrary.Entry saved = LocalLibrary.saveStructure(name, dungeonBytes);
        ThumbnailGenerator.invalidate(saved.id);
        ThumbnailGenerator.ensureThumbnail(saved.id);

        if (mc.screen instanceof SelectorSaveScreen) {
            mc.setScreen(null);
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("DungeonBuilder ").withStyle(ChatFormatting.GREEN)
                            .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                            .append(Component.translatable(messageKey,
                                            Component.literal(saved.name).withStyle(ChatFormatting.AQUA),
                                            Component.literal(saved.id + ".dungeon").withStyle(ChatFormatting.YELLOW))
                                    .withStyle(ChatFormatting.WHITE)),
                    false
            );
        }
    }

    private static void handlePlaceResult(Minecraft mc, PlaceResultPayload payload) {
        if (mc.player == null) {
            return;
        }
        if (payload.success()) {
            mc.player.displayClientMessage(
                    Component.literal("DungeonBuilder ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                            .append(Component.translatable(payload.messageKey(),
                                            Component.literal(payload.arg()).withStyle(ChatFormatting.YELLOW))
                                    .withStyle(ChatFormatting.WHITE)),
                    true
            );
        } else {
            mc.player.displayClientMessage(
                    Component.translatable(payload.messageKey()).withStyle(ChatFormatting.RED),
                    false
            );
        }
    }

    private static void showSaveError(Minecraft mc, Component errorMsg) {
        if (mc.screen instanceof SelectorSaveScreen saveScreen) {
            saveScreen.showError(errorMsg);
        } else if (mc.player != null) {
            mc.player.displayClientMessage(errorMsg.copy().withStyle(ChatFormatting.RED), false);
        }
    }
}