package dev.dungeonbuilder.structure;

import dev.dungeonbuilder.loot.LootModels.ChestLootConfig;
import dev.dungeonbuilder.loot.LootModels.LootEntry;
import dev.dungeonbuilder.loot.LootModels.LootMode;
import dev.dungeonbuilder.loot.LootModels.LootProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Representación en memoria de un archivo .dungeon (v1) listo para previsualizar, configurar loot o colocar. */
public final class DungeonStructure {
    public record BlockEntityRecord(int x, int y, int z, CompoundTag data) {}

    private final String name;
    private final String author;
    private final int width;
    private final int height;
    private final int depth;
    private final int nonAirBlocks;
    private final List<BlockState> palette;
    private final int[] blocks;
    private final List<BlockEntityRecord> blockEntities;
    private final List<String> requiredMods;
    private final Map<BlockPos, ChestLootConfig> chestLootConfigs;
    private final Map<String, LootProfile> embeddedProfiles;

    private DungeonStructure(
            String name,
            String author,
            int width,
            int height,
            int depth,
            int nonAirBlocks,
            List<BlockState> palette,
            int[] blocks,
            List<BlockEntityRecord> blockEntities,
            List<String> requiredMods,
            Map<BlockPos, ChestLootConfig> chestLootConfigs,
            Map<String, LootProfile> embeddedProfiles
    ) {
        this.name = name;
        this.author = author;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.nonAirBlocks = nonAirBlocks;
        this.palette = palette;
        this.blocks = blocks;
        this.blockEntities = blockEntities;
        this.requiredMods = requiredMods;
        this.chestLootConfigs = chestLootConfigs;
        this.embeddedProfiles = embeddedProfiles;
    }

    public static DungeonStructure fromPath(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        return fromNbt(root, BuiltInRegistries.BLOCK.asLookup());
    }

    public static DungeonStructure fromCompressedBytes(byte[] compressedBytes, HolderGetter<Block> blockLookup) throws IOException {
        CompoundTag root = NbtIo.readCompressed(
                new ByteArrayInputStream(compressedBytes), NbtAccounter.unlimitedHeap());
        return fromNbt(root, blockLookup);
    }

    public static DungeonStructure fromNbt(CompoundTag root, HolderGetter<Block> blockLookup) {
        int version = root.getInt("formatVersion");
        if (version != StructureCapture.FORMAT_VERSION) {
            throw new IllegalArgumentException("Versión de formato .dungeon no soportada: " + version);
        }

        CompoundTag meta = root.getCompound("metadata");
        String name = meta.getString("name");
        String author = meta.getString("author");
        int width = meta.getInt("width");
        int height = meta.getInt("height");
        int depth = meta.getInt("depth");
        int nonAirBlocks = meta.getInt("nonAirBlocks");

        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Dimensiones inválidas en .dungeon");
        }
        long expectedVolume = (long) width * height * depth;
        if (expectedVolume > StructureCapture.MAX_VOLUME) {
            throw new IllegalArgumentException("Volumen excede el máximo permitido: " + expectedVolume);
        }

        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            BlockState state = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i));
            palette.add(state != null ? state : Blocks.AIR.defaultBlockState());
        }

        int[] blocks = root.getIntArray("blocks");
        if (blocks.length != (int) expectedVolume) {
            throw new IllegalArgumentException("Array de bloques corrupto (" + blocks.length + " != " + expectedVolume + ")");
        }

        ListTag beList = root.getList("blockEntities", Tag.TAG_COMPOUND);
        List<BlockEntityRecord> blockEntities = new ArrayList<>(beList.size());
        for (int i = 0; i < beList.size(); i++) {
            CompoundTag entry = beList.getCompound(i);
            blockEntities.add(new BlockEntityRecord(
                    entry.getInt("x"),
                    entry.getInt("y"),
                    entry.getInt("z"),
                    entry.getCompound("data")
            ));
        }

        ListTag modsTag = meta.getList("requiredMods", Tag.TAG_STRING);
        List<String> requiredMods = new ArrayList<>(modsTag.size());
        for (int i = 0; i < modsTag.size(); i++) {
            requiredMods.add(modsTag.getString(i));
        }

        Map<BlockPos, ChestLootConfig> lootConfigs = new HashMap<>();
        if (root.contains("lootConfigs", Tag.TAG_LIST)) {
            ListTag lcList = root.getList("lootConfigs", Tag.TAG_COMPOUND);
            for (int i = 0; i < lcList.size(); i++) {
                CompoundTag lc = lcList.getCompound(i);
                int lx = lc.getInt("x");
                int ly = lc.getInt("y");
                int lz = lc.getInt("z");
                LootMode mode = LootMode.fromString(lc.getString("mode"));
                String profileId = lc.getString("profileId");
                boolean perPlayer = lc.getBoolean("perPlayer");
                lootConfigs.put(new BlockPos(lx, ly, lz), new ChestLootConfig(lx, ly, lz, mode, profileId, perPlayer));
            }
        }

        Map<String, LootProfile> embeddedProfiles = new LinkedHashMap<>();
        if (root.contains("lootProfiles", Tag.TAG_COMPOUND)) {
            CompoundTag lpMap = root.getCompound("lootProfiles");
            for (String key : lpMap.getAllKeys()) {
                CompoundTag pTag = lpMap.getCompound(key);
                String pId = pTag.getString("id");
                if (pId.isBlank()) pId = key;
                String pName = pTag.getString("name");
                if (pName.isBlank()) pName = pId;
                int minRolls = Math.max(1, pTag.getInt("minRolls"));
                int maxRolls = Math.max(minRolls, pTag.getInt("maxRolls"));

                ListTag entriesTag = pTag.getList("entries", Tag.TAG_COMPOUND);
                List<LootEntry> entries = new ArrayList<>(entriesTag.size());
                for (int i = 0; i < entriesTag.size(); i++) {
                    CompoundTag eTag = entriesTag.getCompound(i);
                    entries.add(new LootEntry(
                            eTag.getString("itemSnbt"),
                            eTag.getInt("chancePercent"),
                            eTag.getInt("minCount"),
                            eTag.getInt("maxCount")
                    ));
                }
                embeddedProfiles.put(pId.toLowerCase(Locale.ROOT),
                        new LootProfile(pId, pName, minRolls, maxRolls, entries));
            }
        }

        return new DungeonStructure(
                name, author, width, height, depth, nonAirBlocks,
                palette, blocks, blockEntities, requiredMods,
                lootConfigs, embeddedProfiles
        );
    }

    /** Actualiza las configuraciones de cofres y perfiles incrustados dentro de un .dungeon sin alterar los bloques. */
    public static byte[] updateLootInDungeonBytes(
            byte[] compressedBytes,
            List<ChestLootConfig> configs,
            Map<String, LootProfile> usedProfiles
    ) throws IOException {
        CompoundTag root = NbtIo.readCompressed(
                new ByteArrayInputStream(compressedBytes), NbtAccounter.unlimitedHeap());

        ListTag lcList = new ListTag();
        if (configs != null) {
            for (ChestLootConfig cfg : configs) {
                boolean hasRandom = cfg.mode() == LootMode.RANDOM_LOOT
                        && cfg.profileId() != null && !cfg.profileId().isBlank();
                if (hasRandom || cfg.perPlayer()) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("x", cfg.x());
                    tag.putInt("y", cfg.y());
                    tag.putInt("z", cfg.z());
                    tag.putString("mode", cfg.mode().name());
                    tag.putString("profileId", cfg.profileId() == null ? "" : cfg.profileId());
                    tag.putBoolean("perPlayer", cfg.perPlayer());
                    lcList.add(tag);
                }
            }
        }
        if (lcList.isEmpty()) {
            root.remove("lootConfigs");
        } else {
            root.put("lootConfigs", lcList);
        }

        CompoundTag lpMap = new CompoundTag();
        if (usedProfiles != null) {
            for (LootProfile profile : usedProfiles.values()) {
                if (profile == null || profile.id == null || profile.id.isBlank()) {
                    continue;
                }
                CompoundTag pTag = new CompoundTag();
                pTag.putString("id", profile.id);
                pTag.putString("name", profile.name == null ? profile.id : profile.name);
                pTag.putInt("minRolls", Math.max(1, profile.minRolls));
                pTag.putInt("maxRolls", Math.max(Math.max(1, profile.minRolls), profile.maxRolls));

                ListTag entriesTag = new ListTag();
                if (profile.entries != null) {
                    for (LootEntry e : profile.entries) {
                        CompoundTag eTag = new CompoundTag();
                        eTag.putString("itemSnbt", e.itemSnbt == null ? "" : e.itemSnbt);
                        eTag.putInt("chancePercent", Math.max(1, Math.min(100, e.chancePercent)));
                        eTag.putInt("minCount", Math.max(1, e.minCount));
                        eTag.putInt("maxCount", Math.max(Math.max(1, e.minCount), e.maxCount));
                        entriesTag.add(eTag);
                    }
                }
                pTag.put("entries", entriesTag);
                lpMap.put(profile.id, pTag);
            }
        }
        if (lpMap.isEmpty()) {
            root.remove("lootProfiles");
        } else {
            root.put("lootProfiles", lpMap);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, baos);
        byte[] result = baos.toByteArray();
        if (result.length > StructureCapture.MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("El archivo .dungeon supera el tamaño máximo (16 MB).");
        }
        return result;
    }

    public static boolean isContainerBlockEntity(BlockEntityRecord rec, BlockState state) {
        if (rec == null || state == null) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof DispenserBlock
                || block instanceof HopperBlock) {
            return true;
        }
        if (rec.data().contains("Items", Tag.TAG_LIST)) {
            return true;
        }
        String beId = rec.data().getString("id").toLowerCase(Locale.ROOT);
        if (beId.contains("ender_chest")) {
            return false;
        }
        return beId.contains("chest") || beId.contains("barrel")
                || beId.contains("shulker") || beId.contains("crate");
    }

    public String name() { return name; }
    public String author() { return author; }
    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int nonAirBlocks() { return nonAirBlocks; }
    public List<BlockEntityRecord> blockEntities() { return blockEntities; }
    public List<String> requiredMods() { return requiredMods; }
    public Map<String, LootProfile> embeddedProfiles() { return embeddedProfiles; }

    public ChestLootConfig getChestLootConfig(int rawX, int rawY, int rawZ) {
        return chestLootConfigs.get(new BlockPos(rawX, rawY, rawZ));
    }

    public LootProfile resolveLootProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        return embeddedProfiles.get(profileId.toLowerCase(Locale.ROOT));
    }

    public int rotatedWidth(Rotation rotation) {
        return (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? depth : width;
    }

    public int rotatedDepth(Rotation rotation) {
        return (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? width : depth;
    }

    public BlockState getRawBlockState(int x, int y, int z) {
        int idx = (y * depth + z) * width + x;
        int paletteId = blocks[idx];
        if (paletteId < 0 || paletteId >= palette.size()) {
            return Blocks.AIR.defaultBlockState();
        }
        return palette.get(paletteId);
    }

    /** Devuelve la posición relativa transformada al aplicar una rotación alrededor del eje Y. */
    public BlockPos rotateLocalPos(int x, int y, int z, Rotation rotation) {
        return switch (rotation) {
            case NONE -> new BlockPos(x, y, z);
            case CLOCKWISE_90 -> new BlockPos(depth - 1 - z, y, x);
            case CLOCKWISE_180 -> new BlockPos(width - 1 - x, y, depth - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, width - 1 - x);
        };
    }

    public BlockState getRotatedBlockState(int rawX, int rawY, int rawZ, Rotation rotation) {
        BlockState raw = getRawBlockState(rawX, rawY, rawZ);
        return rotation == Rotation.NONE ? raw : raw.rotate(rotation);
    }

    public static Rotation rotationFromSteps(int steps) {
        return switch (Math.floorMod(steps, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }
}