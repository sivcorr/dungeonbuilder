package dev.dungeonbuilder.serverlibrary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.structure.DungeonStructure;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

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

/** Biblioteca compartida almacenada en el servidor (<gameDir>/DungeonBuilder/server/). */
public final class ServerLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();

    private ServerLibrary() {}

    public static final class Entry {
        public String id;
        public String name;
        public String author;
        public String authorUuid;
        public String category = "General";
        public int width;
        public int height;
        public int depth;
        public int nonAirBlocks;
        public int blockEntityCount;
        public long uploadedAt;
        public long fileSizeBytes;
        public List<String> requiredMods = new ArrayList<>();

        public long volume() {
            return (long) width * height * depth;
        }
    }

    public static Path rootDir() {
        return FabricLoader.getInstance().getGameDir().resolve("DungeonBuilder").resolve("server");
    }

    public static Path structuresDir() {
        return rootDir().resolve("structures");
    }

    public static Path indexFile() {
        return rootDir().resolve("config").resolve("structures.json");
    }

    public static Path structureFile(String id) {
        return structuresDir().resolve(id + ".dungeon");
    }

    public static byte[] readStructureBytes(String id) throws IOException {
        return Files.readAllBytes(structureFile(id));
    }

    public static synchronized void updateStructureBytes(String id, byte[] compressedBytes) throws IOException {
        ensureDirectories();
        Path target = structureFile(id);
        Files.write(target, compressedBytes);
        long newSize = Files.size(target);

        List<Entry> entries = new ArrayList<>(readIndex());
        for (Entry e : entries) {
            if (e != null && id.equals(e.id)) {
                e.fileSizeBytes = newSize;
            }
        }
        writeIndex(entries);
        listStructures();
    }

    private static void ensureDirectories() throws IOException {
        Files.createDirectories(structuresDir());
        Files.createDirectories(indexFile().getParent());
    }

    public static synchronized Entry saveExportedStructure(
            ServerPlayer uploader,
            DungeonStructure structure,
            byte[] compressedBytes,
            String category
    ) throws IOException {
        ensureDirectories();

        String cleanName = (structure.name() == null || structure.name().isBlank())
                ? "Estructura"
                : structure.name().trim();
        String cleanCategory = (category == null || category.isBlank())
                ? "General"
                : category.trim();

        String id = uniqueId(slugify(cleanName));
        Path target = structureFile(id);
        Files.write(target, compressedBytes);

        Entry entry = new Entry();
        entry.id = id;
        entry.name = cleanName;
        entry.author = uploader.getScoreboardName();
        entry.authorUuid = uploader.getUUID().toString();
        entry.category = cleanCategory;
        entry.width = structure.width();
        entry.height = structure.height();
        entry.depth = structure.depth();
        entry.nonAirBlocks = structure.nonAirBlocks();
        entry.blockEntityCount = structure.blockEntities().size();
        entry.uploadedAt = System.currentTimeMillis();
        entry.fileSizeBytes = Files.size(target);
        entry.requiredMods = new ArrayList<>(structure.requiredMods());

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
            DungeonBuilder.LOGGER.error("No se pudo crear la carpeta DungeonBuilder/server", e);
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
                                DungeonStructure parsed = DungeonStructure.fromPath(path);
                                Entry rebuilt = new Entry();
                                rebuilt.id = id;
                                rebuilt.name = parsed.name().isBlank() ? id : parsed.name();
                                rebuilt.author = parsed.author().isBlank() ? "-" : parsed.author();
                                rebuilt.authorUuid = "";
                                rebuilt.category = "General";
                                rebuilt.width = parsed.width();
                                rebuilt.height = parsed.height();
                                rebuilt.depth = parsed.depth();
                                rebuilt.nonAirBlocks = parsed.nonAirBlocks();
                                rebuilt.blockEntityCount = parsed.blockEntities().size();
                                rebuilt.uploadedAt = Files.getLastModifiedTime(path).toMillis();
                                rebuilt.fileSizeBytes = size;
                                rebuilt.requiredMods = new ArrayList<>(parsed.requiredMods());
                                current.add(rebuilt);
                            }
                        } catch (Exception ex) {
                            DungeonBuilder.LOGGER.warn("No se pudo leer la estructura del servidor {}", fileName, ex);
                        }
                    });
        } catch (IOException e) {
            DungeonBuilder.LOGGER.error("Error listando estructuras del servidor", e);
        }

        current.sort(Comparator.comparingLong((Entry e) -> e.uploadedAt).reversed());
        writeIndex(current);
        return current;
    }

    public static synchronized boolean deleteStructure(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            ensureDirectories();
            boolean deleted = Files.deleteIfExists(structureFile(id));
            List<Entry> updated = new ArrayList<>(listStructures());
            updated.removeIf(e -> id.equals(e.id));
            writeIndex(updated);
            return deleted;
        } catch (IOException e) {
            DungeonBuilder.LOGGER.error("Error eliminando estructura del servidor {}", id, e);
            return false;
        }
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
            DungeonBuilder.LOGGER.warn("No se pudo leer structures.json del servidor, se reconstruirá.", e);
            return List.of();
        }
    }

    private static void writeIndex(List<Entry> entries) {
        try (Writer writer = Files.newBufferedWriter(indexFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(entries, ENTRY_LIST_TYPE, writer);
        } catch (IOException e) {
            DungeonBuilder.LOGGER.warn("No se pudo actualizar structures.json del servidor", e);
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