package dev.dungeonbuilder.network;

import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.export.ExporterManager;
import dev.dungeonbuilder.loot.LootModels.ChestLootConfig;
import dev.dungeonbuilder.loot.LootModels.LootEntry;
import dev.dungeonbuilder.loot.LootModels.LootMode;
import dev.dungeonbuilder.loot.LootModels.LootProfile;
import dev.dungeonbuilder.loot.LootProfileStorage;
import dev.dungeonbuilder.permissions.PermissionsManager;
import dev.dungeonbuilder.permissions.PermissionsManager.PermissionsSnapshot;
import dev.dungeonbuilder.permissions.PermissionsManager.PlayerAssignment;
import dev.dungeonbuilder.permissions.PermissionsManager.PlayerRoleInfo;
import dev.dungeonbuilder.permissions.PermissionsManager.RolePerms;
import dev.dungeonbuilder.placement.PlacementManager;
import dev.dungeonbuilder.selection.SelectionData;
import dev.dungeonbuilder.selection.SelectionManager;
import dev.dungeonbuilder.serverlibrary.ServerLibrary;
import dev.dungeonbuilder.structure.DungeonStructure;
import dev.dungeonbuilder.structure.StructureCapture;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Definición y registro de paquetes de red entre servidor y cliente. */
public final class ModNetworking {
    public static final int UPLOAD_CHUNK_SIZE = 20_000;
    public static final int SAVE_CHUNK_SIZE = 500_000;
    private static final Map<UUID, PendingUpload> PENDING_UPLOADS = new ConcurrentHashMap<>();

    private ModNetworking() {}

    private static final class PendingUpload {
        final UUID playerId;
        final int totalChunks;
        final byte[][] chunks;
        int received = 0;

        PendingUpload(UUID playerId, int totalChunks) {
            this.playerId = playerId;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }
    }

    public record ServerEntryInfo(
            String id,
            String name,
            String author,
            String category,
            int width,
            int height,
            int depth,
            int nonAirBlocks,
            int blockEntityCount,
            long uploadedAt,
            long fileSizeBytes,
            List<String> requiredMods
    ) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerEntryInfo> CODEC = StreamCodec.of(
                (buf, info) -> {
                    buf.writeUtf(info.id(), 128);
                    buf.writeUtf(info.name(), 128);
                    buf.writeUtf(info.author(), 64);
                    buf.writeUtf(info.category(), 32);
                    buf.writeVarInt(info.width());
                    buf.writeVarInt(info.height());
                    buf.writeVarInt(info.depth());
                    buf.writeVarInt(info.nonAirBlocks());
                    buf.writeVarInt(info.blockEntityCount());
                    buf.writeLong(info.uploadedAt());
                    buf.writeLong(info.fileSizeBytes());
                    buf.writeVarInt(info.requiredMods().size());
                    for (String mod : info.requiredMods()) {
                        buf.writeUtf(mod, 64);
                    }
                },
                buf -> {
                    String id = buf.readUtf(128);
                    String name = buf.readUtf(128);
                    String author = buf.readUtf(64);
                    String category = buf.readUtf(32);
                    int width = buf.readVarInt();
                    int height = buf.readVarInt();
                    int depth = buf.readVarInt();
                    int nonAirBlocks = buf.readVarInt();
                    int blockEntityCount = buf.readVarInt();
                    long uploadedAt = buf.readLong();
                    long fileSizeBytes = buf.readLong();
                    int modCount = Math.min(256, Math.max(0, buf.readVarInt()));
                    List<String> mods = new ArrayList<>(modCount);
                    for (int i = 0; i < modCount; i++) {
                        mods.add(buf.readUtf(64));
                    }
                    return new ServerEntryInfo(
                            id, name, author, category,
                            width, height, depth, nonAirBlocks, blockEntityCount,
                            uploadedAt, fileSizeBytes, mods
                    );
                }
        );

        public long volume() {
            return (long) width * height * depth;
        }
    }

    public record ServerContainerInfo(
            int x,
            int y,
            int z,
            String blockName,
            List<String> originalItemsSnbt,
            String modeName,
            String profileId,
            boolean perPlayer
    ) {}

    private static void writeRolePerms(RegistryFriendlyByteBuf buf, RolePerms rp) {
        buf.writeBoolean(rp.view());
        buf.writeBoolean(rp.preview());
        buf.writeBoolean(rp.place());
        buf.writeBoolean(rp.export());
        buf.writeBoolean(rp.delete());
        buf.writeBoolean(rp.placeLocal());
        buf.writeBoolean(rp.editServerLoot());
    }

    private static RolePerms readRolePerms(RegistryFriendlyByteBuf buf) {
        return new RolePerms(
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean()
        );
    }

    private static void writeLootProfile(RegistryFriendlyByteBuf buf, LootProfile p) {
        buf.writeUtf(p.id == null ? "" : p.id, 64);
        buf.writeUtf(p.name == null ? "" : p.name, 64);
        buf.writeVarInt(Math.max(1, p.minRolls));
        buf.writeVarInt(Math.max(Math.max(1, p.minRolls), p.maxRolls));
        List<LootEntry> entries = p.entries == null ? List.of() : p.entries;
        buf.writeVarInt(entries.size());
        for (LootEntry e : entries) {
            buf.writeUtf(e.itemSnbt == null ? "" : e.itemSnbt, 32767);
            buf.writeVarInt(Math.max(1, Math.min(100, e.chancePercent)));
            buf.writeVarInt(Math.max(1, e.minCount));
            buf.writeVarInt(Math.max(Math.max(1, e.minCount), e.maxCount));
        }
    }

    private static LootProfile readLootProfile(RegistryFriendlyByteBuf buf) {
        String id = buf.readUtf(64);
        String name = buf.readUtf(64);
        int minRolls = Math.max(1, buf.readVarInt());
        int maxRolls = Math.max(minRolls, buf.readVarInt());
        int count = Math.min(512, Math.max(0, buf.readVarInt()));
        List<LootEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new LootEntry(
                    buf.readUtf(32767),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt()
            ));
        }
        return new LootProfile(id, name, minRolls, maxRolls, entries);
    }

    public static List<ServerEntryInfo> buildServerEntryInfoList() {
        List<ServerLibrary.Entry> raw = ServerLibrary.listStructures();
        List<ServerEntryInfo> out = new ArrayList<>(raw.size());
        for (ServerLibrary.Entry e : raw) {
            out.add(new ServerEntryInfo(
                    e.id == null ? "" : e.id,
                    e.name == null ? "-" : e.name,
                    e.author == null ? "-" : e.author,
                    e.category == null ? "General" : e.category,
                    e.width,
                    e.height,
                    e.depth,
                    e.nonAirBlocks,
                    e.blockEntityCount,
                    e.uploadedAt,
                    e.fileSizeBytes,
                    e.requiredMods == null ? List.of() : List.copyOf(e.requiredMods)
            ));
        }
        return out;
    }

    public static boolean canPlaceServerStructure(ServerPlayer player) {
        return PermissionsManager.canPlaceServer(player);
    }

    public static boolean canDeleteServerStructure(ServerPlayer player) {
        return PermissionsManager.canDeleteServer(player);
    }

    public static boolean canEditServerLoot(ServerPlayer player) {
        return PermissionsManager.canEditServerLoot(player);
    }

    private static boolean canPlaceLocalStructure(ServerPlayer player) {
        return PermissionsManager.canPlaceLocal(player);
    }

    private static boolean isSafeStructureId(String id) {
        return id != null && !id.isBlank() && id.matches("[a-zA-Z0-9_.-]+") && !id.contains("..");
    }

    public record SyncSelectionPayload(
            boolean active,
            String dimensionId,
            BlockPos firstCorner,
            boolean hasSecondCorner,
            BlockPos secondCorner
    ) implements CustomPacketPayload {
        public static final Type<SyncSelectionPayload> TYPE =
                new Type<>(DungeonBuilder.id("sync_selection"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SyncSelectionPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, SyncSelectionPayload::active,
                        ByteBufCodecs.stringUtf8(128), SyncSelectionPayload::dimensionId,
                        BlockPos.STREAM_CODEC, SyncSelectionPayload::firstCorner,
                        ByteBufCodecs.BOOL, SyncSelectionPayload::hasSecondCorner,
                        BlockPos.STREAM_CODEC, SyncSelectionPayload::secondCorner,
                        SyncSelectionPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record UndoStatePayload(boolean available, String structureName) implements CustomPacketPayload {
        public static final Type<UndoStatePayload> TYPE =
                new Type<>(DungeonBuilder.id("undo_state"));

        public static final StreamCodec<RegistryFriendlyByteBuf, UndoStatePayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, UndoStatePayload::available,
                        ByteBufCodecs.stringUtf8(128), UndoStatePayload::structureName,
                        UndoStatePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestUndoPayload() implements CustomPacketPayload {
        public static final Type<RequestUndoPayload> TYPE =
                new Type<>(DungeonBuilder.id("request_undo"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestUndoPayload> CODEC =
                StreamCodec.unit(new RequestUndoPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenSelectorSavePayload(
            BlockPos min,
            BlockPos max,
            int width,
            int height,
            int depth
    ) implements CustomPacketPayload {
        public static final Type<OpenSelectorSavePayload> TYPE =
                new Type<>(DungeonBuilder.id("open_selector_save"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenSelectorSavePayload> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, OpenSelectorSavePayload::min,
                        BlockPos.STREAM_CODEC, OpenSelectorSavePayload::max,
                        ByteBufCodecs.VAR_INT, OpenSelectorSavePayload::width,
                        ByteBufCodecs.VAR_INT, OpenSelectorSavePayload::height,
                        ByteBufCodecs.VAR_INT, OpenSelectorSavePayload::depth,
                        OpenSelectorSavePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenExporterPayload(boolean canExport) implements CustomPacketPayload {
        public static final Type<OpenExporterPayload> TYPE =
                new Type<>(DungeonBuilder.id("open_exporter"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenExporterPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, OpenExporterPayload::canExport,
                        OpenExporterPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenServerLibraryPayload(
            boolean canPreview,
            boolean canPlace,
            boolean canDelete,
            boolean canEditServerLoot,
            String roleName,
            List<ServerEntryInfo> entries
    ) implements CustomPacketPayload {
        public static final Type<OpenServerLibraryPayload> TYPE =
                new Type<>(DungeonBuilder.id("open_server_library"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenServerLibraryPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.canPreview());
                    buf.writeBoolean(p.canPlace());
                    buf.writeBoolean(p.canDelete());
                    buf.writeBoolean(p.canEditServerLoot());
                    buf.writeUtf(p.roleName(), 32);
                    buf.writeVarInt(p.entries().size());
                    for (ServerEntryInfo entry : p.entries()) {
                        ServerEntryInfo.CODEC.encode(buf, entry);
                    }
                },
                buf -> {
                    boolean canPreview = buf.readBoolean();
                    boolean canPlace = buf.readBoolean();
                    boolean canDelete = buf.readBoolean();
                    boolean canEditLoot = buf.readBoolean();
                    String roleName = buf.readUtf(32);
                    int count = Math.min(4096, Math.max(0, buf.readVarInt()));
                    List<ServerEntryInfo> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        entries.add(ServerEntryInfo.CODEC.decode(buf));
                    }
                    return new OpenServerLibraryPayload(canPreview, canPlace, canDelete, canEditLoot, roleName, entries);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ServerLibraryListPayload(
            boolean canView,
            boolean canPreview,
            boolean canPlace,
            boolean canDelete,
            boolean canEditServerLoot,
            String roleName,
            List<ServerEntryInfo> entries
    ) implements CustomPacketPayload {
        public static final Type<ServerLibraryListPayload> TYPE =
                new Type<>(DungeonBuilder.id("server_library_list"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerLibraryListPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.canView());
                    buf.writeBoolean(p.canPreview());
                    buf.writeBoolean(p.canPlace());
                    buf.writeBoolean(p.canDelete());
                    buf.writeBoolean(p.canEditServerLoot());
                    buf.writeUtf(p.roleName(), 32);
                    buf.writeVarInt(p.entries().size());
                    for (ServerEntryInfo entry : p.entries()) {
                        ServerEntryInfo.CODEC.encode(buf, entry);
                    }
                },
                buf -> {
                    boolean canView = buf.readBoolean();
                    boolean canPreview = buf.readBoolean();
                    boolean canPlace = buf.readBoolean();
                    boolean canDelete = buf.readBoolean();
                    boolean canEditLoot = buf.readBoolean();
                    String roleName = buf.readUtf(32);
                    int count = Math.min(4096, Math.max(0, buf.readVarInt()));
                    List<ServerEntryInfo> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        entries.add(ServerEntryInfo.CODEC.decode(buf));
                    }
                    return new ServerLibraryListPayload(
                            canView, canPreview, canPlace, canDelete, canEditLoot, roleName, entries);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestServerLibraryPayload() implements CustomPacketPayload {
        public static final Type<RequestServerLibraryPayload> TYPE =
                new Type<>(DungeonBuilder.id("request_server_library"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestServerLibraryPayload> CODEC =
                StreamCodec.unit(new RequestServerLibraryPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestServerChestConfigPayload(String structureId) implements CustomPacketPayload {
        public static final Type<RequestServerChestConfigPayload> TYPE =
                new Type<>(DungeonBuilder.id("request_server_chest_config"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestServerChestConfigPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(128), RequestServerChestConfigPayload::structureId,
                        RequestServerChestConfigPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenServerChestConfigPayload(
            String structureId,
            String structureName,
            List<ServerContainerInfo> containers,
            List<LootProfile> profiles
    ) implements CustomPacketPayload {
        public static final Type<OpenServerChestConfigPayload> TYPE =
                new Type<>(DungeonBuilder.id("open_server_chest_config"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenServerChestConfigPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.structureId(), 128);
                    buf.writeUtf(p.structureName(), 128);
                    buf.writeVarInt(p.containers().size());
                    for (ServerContainerInfo c : p.containers()) {
                        buf.writeVarInt(c.x());
                        buf.writeVarInt(c.y());
                        buf.writeVarInt(c.z());
                        buf.writeUtf(c.blockName(), 128);
                        buf.writeVarInt(c.originalItemsSnbt().size());
                        for (String snbt : c.originalItemsSnbt()) {
                            buf.writeUtf(snbt, 32767);
                        }
                        buf.writeUtf(c.modeName(), 32);
                        buf.writeUtf(c.profileId(), 64);
                        buf.writeBoolean(c.perPlayer());
                    }
                    buf.writeVarInt(p.profiles().size());
                    for (LootProfile lp : p.profiles()) {
                        writeLootProfile(buf, lp);
                    }
                },
                buf -> {
                    String structureId = buf.readUtf(128);
                    String structureName = buf.readUtf(128);
                    int cCount = Math.min(8192, Math.max(0, buf.readVarInt()));
                    List<ServerContainerInfo> containers = new ArrayList<>(cCount);
                    for (int i = 0; i < cCount; i++) {
                        int x = buf.readVarInt();
                        int y = buf.readVarInt();
                        int z = buf.readVarInt();
                        String blockName = buf.readUtf(128);
                        int itemCount = Math.min(256, Math.max(0, buf.readVarInt()));
                        List<String> items = new ArrayList<>(itemCount);
                        for (int j = 0; j < itemCount; j++) {
                            items.add(buf.readUtf(32767));
                        }
                        String modeName = buf.readUtf(32);
                        String profileId = buf.readUtf(64);
                        boolean perPlayer = buf.readBoolean();
                        containers.add(new ServerContainerInfo(x, y, z, blockName, items, modeName, profileId, perPlayer));
                    }
                    int pCount = Math.min(512, Math.max(0, buf.readVarInt()));
                    List<LootProfile> profiles = new ArrayList<>(pCount);
                    for (int i = 0; i < pCount; i++) {
                        profiles.add(readLootProfile(buf));
                    }
                    return new OpenServerChestConfigPayload(structureId, structureName, containers, profiles);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveServerChestConfigPayload(
            String structureId,
            List<ChestLootConfig> configs,
            List<LootProfile> usedProfiles
    ) implements CustomPacketPayload {
        public static final Type<SaveServerChestConfigPayload> TYPE =
                new Type<>(DungeonBuilder.id("save_server_chest_config"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SaveServerChestConfigPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.structureId(), 128);
                    buf.writeVarInt(p.configs().size());
                    for (ChestLootConfig cfg : p.configs()) {
                        buf.writeVarInt(cfg.x());
                        buf.writeVarInt(cfg.y());
                        buf.writeVarInt(cfg.z());
                        buf.writeUtf(cfg.mode().name(), 32);
                        buf.writeUtf(cfg.profileId() == null ? "" : cfg.profileId(), 64);
                        buf.writeBoolean(cfg.perPlayer());
                    }
                    buf.writeVarInt(p.usedProfiles().size());
                    for (LootProfile lp : p.usedProfiles()) {
                        writeLootProfile(buf, lp);
                    }
                },
                buf -> {
                    String structureId = buf.readUtf(128);
                    int cCount = Math.min(8192, Math.max(0, buf.readVarInt()));
                    List<ChestLootConfig> configs = new ArrayList<>(cCount);
                    for (int i = 0; i < cCount; i++) {
                        configs.add(new ChestLootConfig(
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                LootMode.fromString(buf.readUtf(32)),
                                buf.readUtf(64),
                                buf.readBoolean()
                        ));
                    }
                    int pCount = Math.min(512, Math.max(0, buf.readVarInt()));
                    List<LootProfile> profiles = new ArrayList<>(pCount);
                    for (int i = 0; i < pCount; i++) {
                        profiles.add(readLootProfile(buf));
                    }
                    return new SaveServerChestConfigPayload(structureId, configs, profiles);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ServerChestSaveResultPayload(boolean success, String message) implements CustomPacketPayload {
        public static final Type<ServerChestSaveResultPayload> TYPE =
                new Type<>(DungeonBuilder.id("server_chest_save_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerChestSaveResultPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ServerChestSaveResultPayload::success,
                        ByteBufCodecs.stringUtf8(256), ServerChestSaveResultPayload::message,
                        ServerChestSaveResultPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestPermissionsDataPayload() implements CustomPacketPayload {
        public static final Type<RequestPermissionsDataPayload> TYPE =
                new Type<>(DungeonBuilder.id("request_permissions_data"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestPermissionsDataPayload> CODEC =
                StreamCodec.unit(new RequestPermissionsDataPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PermissionsDataPayload(PermissionsSnapshot snapshot) implements CustomPacketPayload {
        public static final Type<PermissionsDataPayload> TYPE =
                new Type<>(DungeonBuilder.id("permissions_data"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PermissionsDataPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    PermissionsSnapshot s = p.snapshot();
                    buf.writeUtf(s.defaultRole(), 32);
                    writeRolePerms(buf, s.playerPerms());
                    writeRolePerms(buf, s.builderPerms());
                    writeRolePerms(buf, s.adminPerms());
                    buf.writeVarInt(s.players().size());
                    for (PlayerRoleInfo info : s.players()) {
                        buf.writeUtf(info.name(), 64);
                        buf.writeUtf(info.assignedRole(), 32);
                        buf.writeUtf(info.effectiveRole(), 32);
                        buf.writeBoolean(info.online());
                        buf.writeBoolean(info.forcedOpAdmin());
                    }
                },
                buf -> {
                    String defaultRole = buf.readUtf(32);
                    RolePerms playerPerms = readRolePerms(buf);
                    RolePerms builderPerms = readRolePerms(buf);
                    RolePerms adminPerms = readRolePerms(buf);
                    int count = Math.min(1024, Math.max(0, buf.readVarInt()));
                    List<PlayerRoleInfo> players = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        players.add(new PlayerRoleInfo(
                                buf.readUtf(64),
                                buf.readUtf(32),
                                buf.readUtf(32),
                                buf.readBoolean(),
                                buf.readBoolean()
                        ));
                    }
                    return new PermissionsDataPayload(new PermissionsSnapshot(
                            defaultRole, playerPerms, builderPerms, adminPerms, players
                    ));
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SavePermissionsConfigPayload(
            String defaultRole,
            RolePerms playerPerms,
            RolePerms builderPerms,
            List<PlayerAssignment> assignments
    ) implements CustomPacketPayload {
        public static final Type<SavePermissionsConfigPayload> TYPE =
                new Type<>(DungeonBuilder.id("save_permissions_config"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SavePermissionsConfigPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.defaultRole(), 32);
                    writeRolePerms(buf, p.playerPerms());
                    writeRolePerms(buf, p.builderPerms());
                    buf.writeVarInt(p.assignments().size());
                    for (PlayerAssignment a : p.assignments()) {
                        buf.writeUtf(a.name(), 64);
                        buf.writeUtf(a.role(), 32);
                    }
                },
                buf -> {
                    String defaultRole = buf.readUtf(32);
                    RolePerms playerPerms = readRolePerms(buf);
                    RolePerms builderPerms = readRolePerms(buf);
                    int count = Math.min(1024, Math.max(0, buf.readVarInt()));
                    List<PlayerAssignment> assignments = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        assignments.add(new PlayerAssignment(buf.readUtf(64), buf.readUtf(32)));
                    }
                    return new SavePermissionsConfigPayload(defaultRole, playerPerms, builderPerms, assignments);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PermissionsSaveResultPayload(boolean success, String message) implements CustomPacketPayload {
        public static final Type<PermissionsSaveResultPayload> TYPE =
                new Type<>(DungeonBuilder.id("permissions_save_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PermissionsSaveResultPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, PermissionsSaveResultPayload::success,
                        ByteBufCodecs.stringUtf8(256), PermissionsSaveResultPayload::message,
                        PermissionsSaveResultPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestServerStructurePayload(String structureId, boolean startPreview) implements CustomPacketPayload {
        public static final Type<RequestServerStructurePayload> TYPE =
                new Type<>(DungeonBuilder.id("request_server_structure"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestServerStructurePayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(128), RequestServerStructurePayload::structureId,
                        ByteBufCodecs.BOOL, RequestServerStructurePayload::startPreview,
                        RequestServerStructurePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ServerStructureChunkPayload(
            UUID transferId,
            String structureId,
            boolean startPreview,
            int chunkIndex,
            int totalChunks,
            byte[] data
    ) implements CustomPacketPayload {
        public static final Type<ServerStructureChunkPayload> TYPE =
                new Type<>(DungeonBuilder.id("server_structure_chunk"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerStructureChunkPayload> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, ServerStructureChunkPayload::transferId,
                        ByteBufCodecs.stringUtf8(128), ServerStructureChunkPayload::structureId,
                        ByteBufCodecs.BOOL, ServerStructureChunkPayload::startPreview,
                        ByteBufCodecs.VAR_INT, ServerStructureChunkPayload::chunkIndex,
                        ByteBufCodecs.VAR_INT, ServerStructureChunkPayload::totalChunks,
                        ByteBufCodecs.byteArray(SAVE_CHUNK_SIZE + 1024), ServerStructureChunkPayload::data,
                        ServerStructureChunkPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlaceServerStructurePayload(
            String structureId,
            BlockPos origin,
            int rotationSteps
    ) implements CustomPacketPayload {
        public static final Type<PlaceServerStructurePayload> TYPE =
                new Type<>(DungeonBuilder.id("place_server_structure"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceServerStructurePayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(128), PlaceServerStructurePayload::structureId,
                        BlockPos.STREAM_CODEC, PlaceServerStructurePayload::origin,
                        ByteBufCodecs.VAR_INT, PlaceServerStructurePayload::rotationSteps,
                        PlaceServerStructurePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record DeleteServerStructurePayload(String structureId) implements CustomPacketPayload {
        public static final Type<DeleteServerStructurePayload> TYPE =
                new Type<>(DungeonBuilder.id("delete_server_structure"));

        public static final StreamCodec<RegistryFriendlyByteBuf, DeleteServerStructurePayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(128), DeleteServerStructurePayload::structureId,
                        DeleteServerStructurePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ServerLibraryActionResultPayload(boolean success, String message) implements CustomPacketPayload {
        public static final Type<ServerLibraryActionResultPayload> TYPE =
                new Type<>(DungeonBuilder.id("server_library_action_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerLibraryActionResultPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ServerLibraryActionResultPayload::success,
                        ByteBufCodecs.stringUtf8(256), ServerLibraryActionResultPayload::message,
                        ServerLibraryActionResultPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static ServerLibraryListPayload buildListPayloadFor(ServerPlayer player, List<ServerEntryInfo> entries) {
        return new ServerLibraryListPayload(
                PermissionsManager.canView(player),
                PermissionsManager.canPreview(player),
                canPlaceServerStructure(player),
                canDeleteServerStructure(player),
                canEditServerLoot(player),
                PermissionsManager.getRole(player).name(),
                entries
        );
    }

    private static void broadcastServerLibraryState(MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<ServerEntryInfo> entries = buildServerEntryInfoList();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(online, buildListPayloadFor(online, entries));
        }
    }

    private static void handleRequestServerChestConfig(ServerPlayer player, RequestServerChestConfigPayload payload) {
        if (!canEditServerLoot(player)) {
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "No tienes permisos para editar cofres del servidor."));
            return;
        }
        if (!isSafeStructureId(payload.structureId())
                || !Files.exists(ServerLibrary.structureFile(payload.structureId()))) {
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "Esa estructura ya no existe en el servidor."));
            return;
        }

        try {
            byte[] rawBytes = ServerLibrary.readStructureBytes(payload.structureId());
            HolderLookup.Provider registries = player.serverLevel().registryAccess();
            DungeonStructure structure = DungeonStructure.fromCompressedBytes(
                    rawBytes, player.serverLevel().holderLookup(Registries.BLOCK));

            Map<String, LootProfile> mergedProfiles = new LinkedHashMap<>();
            for (LootProfile p : LootProfileStorage.listProfiles()) {
                mergedProfiles.put(p.id.toLowerCase(Locale.ROOT), p.copy());
            }
            for (LootProfile emb : structure.embeddedProfiles().values()) {
                mergedProfiles.putIfAbsent(emb.id.toLowerCase(Locale.ROOT), emb.copy());
            }

            List<ServerContainerInfo> containers = new ArrayList<>();
            for (DungeonStructure.BlockEntityRecord rec : structure.blockEntities()) {
                BlockState state = structure.getRawBlockState(rec.x(), rec.y(), rec.z());
                if (!DungeonStructure.isContainerBlockEntity(rec, state)) {
                    continue;
                }

                List<String> origSnbt = new ArrayList<>();
                if (rec.data().contains("Items", Tag.TAG_LIST)) {
                    ListTag itemsTag = rec.data().getList("Items", Tag.TAG_COMPOUND);
                    for (int i = 0; i < itemsTag.size(); i++) {
                        ItemStack parsed = ItemStack.parseOptional(registries, itemsTag.getCompound(i));
                        if (!parsed.isEmpty()) {
                            Tag saved = parsed.save(registries);
                            if (saved instanceof CompoundTag ct) {
                                origSnbt.add(ct.toString());
                            }
                        }
                    }
                }

                ChestLootConfig cfg = structure.getChestLootConfig(rec.x(), rec.y(), rec.z());
                String modeName = cfg != null ? cfg.mode().name() : LootMode.ORIGINAL.name();
                String profileId = cfg != null && cfg.profileId() != null ? cfg.profileId() : "";
                boolean perPlayer = cfg != null && cfg.perPlayer();

                containers.add(new ServerContainerInfo(
                        rec.x(), rec.y(), rec.z(),
                        state.getBlock().getName().getString(),
                        origSnbt,
                        modeName,
                        profileId,
                        perPlayer
                ));
            }

            ServerPlayNetworking.send(player, new OpenServerChestConfigPayload(
                    payload.structureId(),
                    structure.name().isBlank() ? payload.structureId() : structure.name(),
                    containers,
                    new ArrayList<>(mergedProfiles.values())
            ));
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error leyendo cofres del servidor para {}", payload.structureId(), e);
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "No se pudieron leer los cofres de la estructura del servidor."));
        }
    }

    private static void handleSaveServerChestConfig(ServerPlayer player, SaveServerChestConfigPayload payload) {
        if (!canEditServerLoot(player)) {
            ServerPlayNetworking.send(player, new ServerChestSaveResultPayload(
                    false, "No tienes permisos para editar cofres del servidor."));
            return;
        }
        if (!isSafeStructureId(payload.structureId())
                || !Files.exists(ServerLibrary.structureFile(payload.structureId()))) {
            ServerPlayNetworking.send(player, new ServerChestSaveResultPayload(
                    false, "La estructura ya no existe en el servidor."));
            return;
        }

        try {
            byte[] rawBytes = ServerLibrary.readStructureBytes(payload.structureId());
            Map<String, LootProfile> profileMap = new LinkedHashMap<>();
            for (LootProfile p : payload.usedProfiles()) {
                if (p != null && p.id != null && !p.id.isBlank()) {
                    profileMap.put(p.id.toLowerCase(Locale.ROOT), p);
                }
            }

            byte[] updated = DungeonStructure.updateLootInDungeonBytes(rawBytes, payload.configs(), profileMap);
            ServerLibrary.updateStructureBytes(payload.structureId(), updated);

            DungeonBuilder.LOGGER.info("Jugador {} actualizó los cofres/loot de '{}' en el servidor.",
                    player.getScoreboardName(), payload.structureId());

            ServerPlayNetworking.send(player, new ServerChestSaveResultPayload(
                    true, "¡Cofres del servidor guardados correctamente!"
            ));
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error guardando cofres del servidor en {}", payload.structureId(), e);
            ServerPlayNetworking.send(player, new ServerChestSaveResultPayload(
                    false, "Ocurrió un error al guardar los cofres en el servidor."
            ));
        }
    }

    private static void handleRequestPermissionsData(ServerPlayer player) {
        if (!PermissionsManager.isAdmin(player)) {
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "Solo los administradores pueden configurar permisos."));
            return;
        }
        ServerPlayNetworking.send(player, new PermissionsDataPayload(
                PermissionsManager.buildSnapshot(player.server)
        ));
    }

    private static void handleSavePermissionsConfig(ServerPlayer player, SavePermissionsConfigPayload payload) {
        if (!PermissionsManager.isAdmin(player)) {
            ServerPlayNetworking.send(player, new PermissionsSaveResultPayload(
                    false, "No tienes permisos de administrador para guardar cambios."));
            return;
        }
        try {
            PermissionsManager.applyFromGui(
                    payload.defaultRole(),
                    payload.playerPerms(),
                    payload.builderPerms(),
                    payload.assignments()
            );
            DungeonBuilder.LOGGER.info("Administrador {} actualizó permissions.json desde la interfaz.",
                    player.getScoreboardName());

            ServerPlayNetworking.send(player, new PermissionsDataPayload(
                    PermissionsManager.buildSnapshot(player.server)
            ));
            ServerPlayNetworking.send(player, new PermissionsSaveResultPayload(
                    true, "¡Permisos guardados y aplicados al instante!"
            ));
            broadcastServerLibraryState(player.server);
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error guardando permisos desde la GUI para {}",
                    player.getScoreboardName(), e);
            ServerPlayNetworking.send(player, new PermissionsSaveResultPayload(
                    false, "Error guardando permissions.json en el servidor."
            ));
        }
    }

    private static void handleRequestServerLibrary(ServerPlayer player) {
        PlacementManager.syncUndoState(player);
        if (!PermissionsManager.canView(player)) {
            ServerPlayNetworking.send(player, buildListPayloadFor(player, List.of()));
            return;
        }
        ServerPlayNetworking.send(player, buildListPayloadFor(player, buildServerEntryInfoList()));
    }

    private static void handleRequestServerStructure(ServerPlayer player, RequestServerStructurePayload payload) {
        if (!PermissionsManager.canView(player)) {
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "No tienes permisos para consultar estructuras del servidor."));
            return;
        }
        if (payload.startPreview() && !PermissionsManager.canPreview(player)) {
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "No tienes permisos para previsualizar estructuras del servidor."));
            return;
        }
        if (!isSafeStructureId(payload.structureId())
                || !Files.exists(ServerLibrary.structureFile(payload.structureId()))) {
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "Esa estructura ya no existe en el servidor."));
            return;
        }

        try {
            byte[] compressed = ServerLibrary.readStructureBytes(payload.structureId());
            UUID transferId = UUID.randomUUID();
            int totalChunks = Math.max(1, (int) Math.ceil((double) compressed.length / SAVE_CHUNK_SIZE));
            for (int i = 0; i < totalChunks; i++) {
                int start = i * SAVE_CHUNK_SIZE;
                int end = Math.min(compressed.length, start + SAVE_CHUNK_SIZE);
                byte[] slice = Arrays.copyOfRange(compressed, start, end);
                ServerPlayNetworking.send(player, new ServerStructureChunkPayload(
                        transferId, payload.structureId(), payload.startPreview(), i, totalChunks, slice));
            }
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error enviando estructura '{}' al cliente {}",
                    payload.structureId(), player.getScoreboardName(), e);
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "No se pudo descargar la estructura del servidor."));
        }
    }

    private static void handlePlaceServerStructure(ServerPlayer player, PlaceServerStructurePayload payload) {
        if (!canPlaceServerStructure(player)) {
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_no_place_permission", ""));
            return;
        }
        if (PlacementManager.hasActiveJob(player.getUUID())) {
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_place_busy", ""));
            return;
        }
        if (!isSafeStructureId(payload.structureId())
                || !Files.exists(ServerLibrary.structureFile(payload.structureId()))) {
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_place_failed", ""));
            return;
        }

        try {
            byte[] compressed = ServerLibrary.readStructureBytes(payload.structureId());
            DungeonStructure structure = DungeonStructure.fromCompressedBytes(
                    compressed,
                    player.serverLevel().holderLookup(Registries.BLOCK)
            );
            Rotation rotation = DungeonStructure.rotationFromSteps(payload.rotationSteps());
            PlacementManager.enqueue(player, player.serverLevel(), payload.origin(), rotation, structure);

            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    true, "message.dungeonbuilder.place_started", structure.name()));
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error colocando estructura del servidor '{}' para {}",
                    payload.structureId(), player.getScoreboardName(), e);
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_place_failed", ""));
        }
    }

    private static void handleDeleteServerStructure(ServerPlayer player, DeleteServerStructurePayload payload) {
        if (!canDeleteServerStructure(player)) {
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "No tienes permisos para eliminar estructuras del servidor."));
            return;
        }
        if (!isSafeStructureId(payload.structureId())) {
            ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                    false, "Identificador de estructura inválido."));
            return;
        }

        boolean deleted = ServerLibrary.deleteStructure(payload.structureId());
        broadcastServerLibraryState(player.server);
        ServerPlayNetworking.send(player, new ServerLibraryActionResultPayload(
                deleted,
                deleted ? "Estructura eliminada del servidor." : "La estructura ya no existía en el servidor."
        ));
    }

    private static void handlePlaceChunk(ServerPlayer player, PlaceChunkPayload payload) {
        if (!canPlaceLocalStructure(player)) {
            PENDING_UPLOADS.remove(payload.transferId());
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_no_place_permission", ""));
            return;
        }
        if (PlacementManager.hasActiveJob(player.getUUID())) {
            PENDING_UPLOADS.remove(payload.transferId());
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_place_busy", ""));
            return;
        }
        if (payload.totalChunks() <= 0 || payload.totalChunks() > 1024
                || payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.totalChunks()) {
            PENDING_UPLOADS.remove(payload.transferId());
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_place_failed", ""));
            return;
        }

        PendingUpload upload = PENDING_UPLOADS.computeIfAbsent(payload.transferId(),
                id -> new PendingUpload(player.getUUID(), payload.totalChunks()));

        if (!upload.playerId.equals(player.getUUID()) || upload.totalChunks != payload.totalChunks()) {
            PENDING_UPLOADS.remove(payload.transferId());
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_place_failed", ""));
            return;
        }

        if (upload.chunks[payload.chunkIndex()] == null) {
            upload.chunks[payload.chunkIndex()] = payload.data();
            upload.received++;
        }

        if (upload.received < upload.totalChunks) {
            return;
        }

        PENDING_UPLOADS.remove(payload.transferId());
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (byte[] chunk : upload.chunks) {
                if (chunk == null) {
                    throw new IllegalStateException("Chunk faltante");
                }
                baos.write(chunk);
                if (baos.size() > StructureCapture.MAX_COMPRESSED_BYTES) {
                    throw new IllegalArgumentException("Estructura supera el tamaño máximo");
                }
            }

            DungeonStructure structure = DungeonStructure.fromCompressedBytes(
                    baos.toByteArray(),
                    player.serverLevel().holderLookup(Registries.BLOCK)
            );
            Rotation rotation = DungeonStructure.rotationFromSteps(payload.rotationSteps());
            PlacementManager.enqueue(player, player.serverLevel(), payload.origin(), rotation, structure);

            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    true, "message.dungeonbuilder.place_started", structure.name()));
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error procesando colocación local para {}", player.getScoreboardName(), e);
            ServerPlayNetworking.send(player, new PlaceResultPayload(
                    false, "message.dungeonbuilder.error_place_failed", ""));
        }
    }

    private static void handleSaveRequest(ServerPlayer player, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            ServerPlayNetworking.send(player, new SaveStructureResultPayload(
                    false, "gui.dungeonbuilder.selector.error_empty_name", "", new byte[0]));
            return;
        }
        if (name.length() > 48) {
            name = name.substring(0, 48).trim();
        }

        if (!SelectionManager.hasCompleteSelectionIn(player.getUUID(), player.serverLevel().dimension())) {
            ServerPlayNetworking.send(player, new SaveStructureResultPayload(
                    false, "message.dungeonbuilder.error_no_selection", "", new byte[0]));
            return;
        }

        SelectionData selection = SelectionManager.getSelection(player.getUUID());
        long volume = selection.volume();
        if (volume > StructureCapture.MAX_VOLUME) {
            ServerPlayNetworking.send(player, new SaveStructureResultPayload(
                    false,
                    "message.dungeonbuilder.error_too_large",
                    volume + "|" + StructureCapture.MAX_VOLUME,
                    new byte[0]
            ));
            return;
        }

        try {
            byte[] compressed = StructureCapture.captureToCompressedDungeon(
                    player.serverLevel(), player, selection, name);

            UUID transferId = UUID.randomUUID();
            int totalChunks = Math.max(1, (int) Math.ceil((double) compressed.length / SAVE_CHUNK_SIZE));
            for (int i = 0; i < totalChunks; i++) {
                int start = i * SAVE_CHUNK_SIZE;
                int end = Math.min(compressed.length, start + SAVE_CHUNK_SIZE);
                byte[] slice = Arrays.copyOfRange(compressed, start, end);
                ServerPlayNetworking.send(player, new SaveStructureChunkPayload(
                        transferId, i, totalChunks, name, slice));
            }
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error capturando estructura '{}' para {}", name, player.getScoreboardName(), e);
            ServerPlayNetworking.send(player, new SaveStructureResultPayload(
                    false,
                    "message.dungeonbuilder.error_capture_failed",
                    e.getMessage() == null ? "IO" : e.getMessage(),
                    new byte[0]
            ));
        }
    }

    public record MarkCornerPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<MarkCornerPayload> TYPE =
                new Type<>(DungeonBuilder.id("mark_corner"));

        public static final StreamCodec<RegistryFriendlyByteBuf, MarkCornerPayload> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, MarkCornerPayload::pos,
                        MarkCornerPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenPersonalLibraryPayload() implements CustomPacketPayload {
        public static final Type<OpenPersonalLibraryPayload> TYPE =
                new Type<>(DungeonBuilder.id("open_personal_library"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPersonalLibraryPayload> CODEC =
                StreamCodec.unit(new OpenPersonalLibraryPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestSaveStructurePayload(String structureName) implements CustomPacketPayload {
        public static final Type<RequestSaveStructurePayload> TYPE =
                new Type<>(DungeonBuilder.id("request_save_structure"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestSaveStructurePayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(64), RequestSaveStructurePayload::structureName,
                        RequestSaveStructurePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveStructureResultPayload(boolean success, String messageKey, String arg, byte[] dungeonBytes)
            implements CustomPacketPayload {
        public static final Type<SaveStructureResultPayload> TYPE =
                new Type<>(DungeonBuilder.id("save_structure_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SaveStructureResultPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, SaveStructureResultPayload::success,
                        ByteBufCodecs.stringUtf8(128), SaveStructureResultPayload::messageKey,
                        ByteBufCodecs.stringUtf8(128), SaveStructureResultPayload::arg,
                        ByteBufCodecs.byteArray(SAVE_CHUNK_SIZE), SaveStructureResultPayload::dungeonBytes,
                        SaveStructureResultPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveStructureChunkPayload(
            UUID transferId,
            int chunkIndex,
            int totalChunks,
            String structureName,
            byte[] data
    ) implements CustomPacketPayload {
        public static final Type<SaveStructureChunkPayload> TYPE =
                new Type<>(DungeonBuilder.id("save_structure_chunk"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SaveStructureChunkPayload> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, SaveStructureChunkPayload::transferId,
                        ByteBufCodecs.VAR_INT, SaveStructureChunkPayload::chunkIndex,
                        ByteBufCodecs.VAR_INT, SaveStructureChunkPayload::totalChunks,
                        ByteBufCodecs.stringUtf8(64), SaveStructureChunkPayload::structureName,
                        ByteBufCodecs.byteArray(SAVE_CHUNK_SIZE + 1024), SaveStructureChunkPayload::data,
                        SaveStructureChunkPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlaceChunkPayload(
            UUID transferId,
            int chunkIndex,
            int totalChunks,
            BlockPos origin,
            int rotationSteps,
            byte[] data
    ) implements CustomPacketPayload {
        public static final Type<PlaceChunkPayload> TYPE =
                new Type<>(DungeonBuilder.id("place_chunk"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceChunkPayload> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, PlaceChunkPayload::transferId,
                        ByteBufCodecs.VAR_INT, PlaceChunkPayload::chunkIndex,
                        ByteBufCodecs.VAR_INT, PlaceChunkPayload::totalChunks,
                        BlockPos.STREAM_CODEC, PlaceChunkPayload::origin,
                        ByteBufCodecs.VAR_INT, PlaceChunkPayload::rotationSteps,
                        ByteBufCodecs.byteArray(UPLOAD_CHUNK_SIZE + 1024), PlaceChunkPayload::data,
                        PlaceChunkPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlaceResultPayload(boolean success, String messageKey, String arg) implements CustomPacketPayload {
        public static final Type<PlaceResultPayload> TYPE =
                new Type<>(DungeonBuilder.id("place_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceResultPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, PlaceResultPayload::success,
                        ByteBufCodecs.stringUtf8(128), PlaceResultPayload::messageKey,
                        ByteBufCodecs.stringUtf8(128), PlaceResultPayload::arg,
                        PlaceResultPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ExportChunkPayload(
            UUID transferId,
            int chunkIndex,
            int totalChunks,
            String category,
            byte[] data
    ) implements CustomPacketPayload {
        public static final Type<ExportChunkPayload> TYPE =
                new Type<>(DungeonBuilder.id("export_chunk"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ExportChunkPayload> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, ExportChunkPayload::transferId,
                        ByteBufCodecs.VAR_INT, ExportChunkPayload::chunkIndex,
                        ByteBufCodecs.VAR_INT, ExportChunkPayload::totalChunks,
                        ByteBufCodecs.stringUtf8(32), ExportChunkPayload::category,
                        ByteBufCodecs.byteArray(UPLOAD_CHUNK_SIZE + 1024), ExportChunkPayload::data,
                        ExportChunkPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ExportResultPayload(
            boolean success,
            String message,
            String exportedId
    ) implements CustomPacketPayload {
        public static final Type<ExportResultPayload> TYPE =
                new Type<>(DungeonBuilder.id("export_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ExportResultPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ExportResultPayload::success,
                        ByteBufCodecs.stringUtf8(256), ExportResultPayload::message,
                        ByteBufCodecs.stringUtf8(64), ExportResultPayload::exportedId,
                        ExportResultPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(SyncSelectionPayload.TYPE, SyncSelectionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UndoStatePayload.TYPE, UndoStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenSelectorSavePayload.TYPE, OpenSelectorSavePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenPersonalLibraryPayload.TYPE, OpenPersonalLibraryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenExporterPayload.TYPE, OpenExporterPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenServerLibraryPayload.TYPE, OpenServerLibraryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerLibraryListPayload.TYPE, ServerLibraryListPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerStructureChunkPayload.TYPE, ServerStructureChunkPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerLibraryActionResultPayload.TYPE, ServerLibraryActionResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SaveStructureResultPayload.TYPE, SaveStructureResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SaveStructureChunkPayload.TYPE, SaveStructureChunkPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PlaceResultPayload.TYPE, PlaceResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ExportResultPayload.TYPE, ExportResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PermissionsDataPayload.TYPE, PermissionsDataPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PermissionsSaveResultPayload.TYPE, PermissionsSaveResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenServerChestConfigPayload.TYPE, OpenServerChestConfigPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerChestSaveResultPayload.TYPE, ServerChestSaveResultPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(MarkCornerPayload.TYPE, MarkCornerPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestSaveStructurePayload.TYPE, RequestSaveStructurePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PlaceChunkPayload.TYPE, PlaceChunkPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ExportChunkPayload.TYPE, ExportChunkPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestServerLibraryPayload.TYPE, RequestServerLibraryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestServerStructurePayload.TYPE, RequestServerStructurePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PlaceServerStructurePayload.TYPE, PlaceServerStructurePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DeleteServerStructurePayload.TYPE, DeleteServerStructurePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestPermissionsDataPayload.TYPE, RequestPermissionsDataPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SavePermissionsConfigPayload.TYPE, SavePermissionsConfigPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestUndoPayload.TYPE, RequestUndoPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestServerChestConfigPayload.TYPE, RequestServerChestConfigPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveServerChestConfigPayload.TYPE, SaveServerChestConfigPayload.CODEC);
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(MarkCornerPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        SelectionManager.handleMarkCorner(context.player(), payload.pos())));

        ServerPlayNetworking.registerGlobalReceiver(RequestSaveStructurePayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handleSaveRequest(context.player(), payload.structureName())));

        ServerPlayNetworking.registerGlobalReceiver(PlaceChunkPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handlePlaceChunk(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(ExportChunkPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        ExporterManager.handleExportChunk(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(RequestServerLibraryPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handleRequestServerLibrary(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(RequestServerStructurePayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handleRequestServerStructure(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(PlaceServerStructurePayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handlePlaceServerStructure(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(DeleteServerStructurePayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handleDeleteServerStructure(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(RequestPermissionsDataPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handleRequestPermissionsData(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(SavePermissionsConfigPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handleSavePermissionsConfig(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(RequestUndoPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        PlacementManager.enqueueUndo(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(RequestServerChestConfigPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handleRequestServerChestConfig(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(SaveServerChestConfigPayload.TYPE, (payload, context) ->
                context.server().execute(() ->
                        handleSaveServerChestConfig(context.player(), payload)));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SelectionManager.syncToClient(handler.player);
            PlacementManager.syncUndoState(handler.player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.player.getUUID();
            PENDING_UPLOADS.values().removeIf(up -> up.playerId.equals(playerId));
            ExporterManager.clearPlayer(playerId);
            PlacementManager.clearPlayer(playerId);
        });
    }
}