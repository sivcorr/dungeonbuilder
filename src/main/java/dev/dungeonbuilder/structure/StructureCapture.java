package dev.dungeonbuilder.structure;

import dev.dungeonbuilder.selection.SelectionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Captura una región del mundo y la serializa al formato comprimido .dungeon (v1). */
public final class StructureCapture {
    public static final int FORMAT_VERSION = 1;
    public static final long MAX_VOLUME = 10_000_000L;
    public static final int MAX_COMPRESSED_BYTES = 16 * 1024 * 1024;

    private StructureCapture() {}

    public static byte[] captureToCompressedDungeon(ServerLevel level, ServerPlayer author,
                                                    SelectionData selection, String structureName) throws IOException {
        BlockPos min = selection.min();
        BlockPos max = selection.max();
        int width = selection.width();
        int height = selection.height();
        int depth = selection.depth();
        long volume = selection.volume();

        if (volume <= 0 || volume > MAX_VOLUME) {
            throw new IllegalArgumentException("Volume out of range: " + volume);
        }

        int total = (int) volume;
        int[] blockIndices = new int[total];
        Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
        List<BlockState> paletteStates = new ArrayList<>();
        ListTag blockEntitiesTag = new ListTag();
        Set<String> requiredMods = new LinkedHashSet<>();

        int nonAirBlocks = 0;
        int cursorIndex = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);

                    Integer idx = paletteIndex.get(state);
                    if (idx == null) {
                        idx = paletteStates.size();
                        paletteIndex.put(state, idx);
                        paletteStates.add(state);
                    }
                    blockIndices[cursorIndex++] = idx;

                    if (!state.isAir()) {
                        nonAirBlocks++;
                        String namespace = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
                        requiredMods.add(namespace);
                    }

                    BlockEntity blockEntity = level.getBlockEntity(cursor);
                    if (blockEntity != null) {
                        CompoundTag beEntry = new CompoundTag();
                        beEntry.putInt("x", x - min.getX());
                        beEntry.putInt("y", y - min.getY());
                        beEntry.putInt("z", z - min.getZ());
                        beEntry.put("data", blockEntity.saveWithFullMetadata(level.registryAccess()));
                        blockEntitiesTag.add(beEntry);
                    }
                }
            }
        }

        ListTag paletteTag = new ListTag();
        for (BlockState state : paletteStates) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }

        ListTag modsTag = new ListTag();
        for (String modId : requiredMods) {
            modsTag.add(StringTag.valueOf(modId));
        }

        CompoundTag metadata = new CompoundTag();
        metadata.putString("name", structureName.trim());
        metadata.putString("author", author.getScoreboardName());
        metadata.putString("authorUuid", author.getUUID().toString());
        metadata.putLong("createdAt", System.currentTimeMillis());
        metadata.putInt("width", width);
        metadata.putInt("height", height);
        metadata.putInt("depth", depth);
        metadata.putInt("nonAirBlocks", nonAirBlocks);
        metadata.putInt("blockEntityCount", blockEntitiesTag.size());
        metadata.put("requiredMods", modsTag);

        CompoundTag root = new CompoundTag();
        root.putInt("formatVersion", FORMAT_VERSION);
        root.put("metadata", metadata);
        root.put("palette", paletteTag);
        root.putIntArray("blocks", blockIndices);
        root.put("blockEntities", blockEntitiesTag);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, baos);
        byte[] compressed = baos.toByteArray();

        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Compressed structure exceeds limit: " + compressed.length);
        }
        return compressed;
    }
}