package dev.dungeonbuilder.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.dungeonbuilder.DungeonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Biblioteca personal local guardada en <gameDir>/DungeonBuilder/ independientemente del servidor. */
public final class LocalLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();

    private LocalLibrary() {}

    public static final class Entry {
        public String id;
        public String name;
        public String author;
        public int width;
        public int height;
        public int depth;
        public int nonAirBlocks;
        public int blockEntityCount;
        public long createdAt;
        public long fileSizeBytes;
        public List<String> requiredMods = new ArrayList<>();

        public long volume() {
            return (long) width * height * depth;
        }
    }

    public static Path rootDir() {
        return FabricLoader.getInstance().getGameDir().resolve("DungeonBuilder");
    }

    public static Path structuresDir() {
        return rootDir().resolve("structures");
    }

    public static Path thumbnailsDir() {
        return rootDir().resolve("thumbnails");
    }

    public static Path indexFile() {
        return rootDir().resolve("config").resolve("local_library.json");
    }

    public static Path structureFile(String id) {
        return structuresDir().resolve(id + ".dungeon");
    }

    public static Path thumbnailFile(String id) {
        return thumbnailsDir().resolve(id + ".png");
    }

    public static byte[] readStructureBytes(String id) throws IOException {
        return Files.readAllBytes(structureFile(id));
    }

    public static synchronized void updateStructureBytes(String id, byte[] compressedBytes) throws IOException {
        ensureDirectories();
        Path target = structureFile(id);
        Files.write(target, compressedBytes);
        listStructures();
    }

    private static void ensureDirectories() throws IOException {
        Files.createDirectories(structuresDir());
        Files.createDirectories(thumbnailsDir());
        Files.createDirectories(indexFile().getParent());
    }

    public static synchronized Entry saveStructure(String requestedName, byte[] compressedBytes) throws IOException {
        ensureDirectories();

        CompoundTag root = NbtIo.readCompressed(
                new ByteArrayInputStream(compressedBytes), NbtAccounter.unlimitedHeap());

        String id = uniqueId(slugify(requestedName));
        Path target = structureFile(id);
        Files.write(target, compressedBytes);

        Entry entry = entryFromNbt(id, root, Files.size(target));
        if (entry.name == null || entry.name.isBlank()) {
            entry.name = requestedName;
        }

        List<Entry> entries = new ArrayList<>(listStructures());
        entries.removeIf(e -> id.equals(e.id));
        entries.add(0, entry);
        writeIndex(entries);
        return entry;
    }

    public static synchronized List<Entry> listStructures() {
        try {
            ensureDirectories();
        } catch (IOException e) {
            DungeonBuilder.LOGGER.error("No se pudo crear la carpeta DungeonBuilder", e);
            return List.of();
        }

        Map<String, Entry> cached = new LinkedHashMap<>();
        for (Entry e : readIndex()) {
            if (e != null && e.id != null) {
                cached.put(e.id, e);
            }
        }

        List<Entry> current = new ArrayList<>();
        try (Stream<Path> files = Files.list(structuresDir())) {
            files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".dungeon"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        String id = fileName.substring(0, fileName.length() - ".dungeon".length());
                        try {
                            long size = Files.size(path);
                            Entry existing = cached.get(id);
                            if (existing != null && existing.fileSizeBytes == size) {
                                current.add(existing);
                            } else {
                                CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                                current.add(entryFromNbt(id, root, size));
                            }
                        } catch (Exception ex) {
                            DungeonBuilder.LOGGER.warn("No se pudo leer la estructura local {}", fileName, ex);
                        }
                    });
        } catch (IOException e) {
            DungeonBuilder.LOGGER.error("Error listando estructuras locales", e);
        }

        current.sort(Comparator.comparingLong((Entry e) -> e.createdAt).reversed());
        writeIndex(current);
        return current;
    }

    public static synchronized boolean deleteStructure(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            ensureDirectories();
            Path target = structureFile(id);
            boolean deleted = Files.deleteIfExists(target);
            Files.deleteIfExists(thumbnailFile(id));
            List<Entry> updated = new ArrayList<>(listStructures());
            updated.removeIf(e -> id.equals(e.id));
            writeIndex(updated);
            return deleted;
        } catch (IOException e) {
            DungeonBuilder.LOGGER.error("Error eliminando estructura local {}", id, e);
            return false;
        }
    }

    private static Entry entryFromNbt(String id, CompoundTag root, long fileSize) {
        CompoundTag meta = root.getCompound("metadata");
        Entry entry = new Entry();
        entry.id = id;
        entry.name = meta.contains("name", Tag.TAG_STRING) ? meta.getString("name") : id;
        entry.author = meta.contains("author", Tag.TAG_STRING) ? meta.getString("author") : "-";
        entry.width = meta.getInt("width");
        entry.height = meta.getInt("height");
        entry.depth = meta.getInt("depth");
        entry.nonAirBlocks = meta.getInt("nonAirBlocks");
        entry.blockEntityCount = meta.getInt("blockEntityCount");
        entry.createdAt = meta.contains("createdAt", Tag.TAG_LONG) ? meta.getLong("createdAt") : System.currentTimeMillis();
        entry.fileSizeBytes = fileSize;

        ListTag mods = meta.getList("requiredMods", Tag.TAG_STRING);
        for (int i = 0; i < mods.size(); i++) {
            entry.requiredMods.add(mods.getString(i));
        }
        return entry;
    }

    private static List<Entry> readIndex() {
        Path idx = indexFile();
        if (!Files.exists(idx)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(idx, StandardCharsets.UTF_8)) {
            List<Entry> list = GSON.fromJson(reader, ENTRY_LIST_TYPE);
            return list != null ? list : List.of();
        } catch (Exception e) {
            DungeonBuilder.LOGGER.warn("No se pudo leer local_library.json, se reconstruirá.", e);
            return List.of();
        }
    }

    private static void writeIndex(List<Entry> entries) {
        try (Writer writer = Files.newBufferedWriter(indexFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(entries, ENTRY_LIST_TYPE, writer);
        } catch (IOException e) {
            DungeonBuilder.LOGGER.warn("No se pudo actualizar local_library.json", e);
        }
    }

    private static String slugify(String raw) {
        String normalized = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "estructura" : normalized;
    }

    private static String uniqueId(String base) {
        String candidate = base;
        int suffix = 2;
        while (Files.exists(structureFile(candidate))) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }
}