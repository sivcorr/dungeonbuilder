package dev.dungeonbuilder.loot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.loot.LootModels.LootEntry;
import dev.dungeonbuilder.loot.LootModels.LootProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Almacena perfiles de loot en DungeonBuilder/config/loot_profiles.json y genera loot en contenedores. */
public final class LootProfileStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PROFILE_LIST_TYPE = new TypeToken<List<LootProfile>>() {}.getType();

    private LootProfileStorage() {}

    public static Path configFile() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("DungeonBuilder")
                .resolve("config")
                .resolve("loot_profiles.json");
    }

    public static synchronized List<LootProfile> listProfiles() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                List<LootProfile> defaults = createDefaultProfiles();
                saveProfiles(defaults);
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                List<LootProfile> loaded = GSON.fromJson(reader, PROFILE_LIST_TYPE);
                if (loaded == null || loaded.isEmpty()) {
                    List<LootProfile> defaults = createDefaultProfiles();
                    saveProfiles(defaults);
                    return defaults;
                }
                List<LootProfile> clean = new ArrayList<>();
                for (LootProfile p : loaded) {
                    if (p != null && p.id != null && !p.id.isBlank()) {
                        if (p.name == null || p.name.isBlank()) {
                            p.name = p.id;
                        }
                        if (p.minRolls < 1) p.minRolls = 1;
                        if (p.maxRolls < p.minRolls) p.maxRolls = p.minRolls;
                        if (p.entries == null) p.entries = new ArrayList<>();
                        clean.add(p);
                    }
                }
                return clean.isEmpty() ? createDefaultProfiles() : clean;
            }
        } catch (Exception e) {
            DungeonBuilder.LOGGER.warn("Error leyendo loot_profiles.json, usando valores por defecto.", e);
            return createDefaultProfiles();
        }
    }

    public static synchronized void saveProfiles(List<LootProfile> profiles) throws IOException {
        Path file = configFile();
        Files.createDirectories(file.getParent());
        List<LootProfile> toWrite = profiles == null || profiles.isEmpty()
                ? createDefaultProfiles()
                : profiles;
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(toWrite, PROFILE_LIST_TYPE, writer);
        }
    }

    public static LootProfile getProfileOrFallback(String profileId) {
        List<LootProfile> all = listProfiles();
        if (profileId != null && !profileId.isBlank()) {
            for (LootProfile p : all) {
                if (p.id.equalsIgnoreCase(profileId)) {
                    return p;
                }
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    public static String itemStackToSnbt(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            return "";
        }
        ItemStack single = stack.copy();
        single.setCount(1);
        Tag tag = single.save(registries);
        return tag instanceof CompoundTag ct ? ct.toString() : "";
    }

    public static ItemStack snbtToItemStack(String snbt, HolderLookup.Provider registries) {
        if (snbt == null || snbt.isBlank() || registries == null) {
            return ItemStack.EMPTY;
        }
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            return ItemStack.parseOptional(registries, tag);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    public static void generateLootIntoContainer(
            Container container,
            LootProfile profile,
            RandomSource random,
            HolderLookup.Provider registries
    ) {
        if (container == null || profile == null) {
            return;
        }
        container.clearContent();

        if (profile.entries == null || profile.entries.isEmpty()) {
            container.setChanged();
            return;
        }

        List<LootEntry> validEntries = new ArrayList<>();
        List<ItemStack> validStacks = new ArrayList<>();
        int totalWeight = 0;

        for (LootEntry entry : profile.entries) {
            if (entry == null || entry.chancePercent <= 0) {
                continue;
            }
            ItemStack parsed = snbtToItemStack(entry.itemSnbt, registries);
            if (!parsed.isEmpty()) {
                validEntries.add(entry);
                validStacks.add(parsed);
                totalWeight += entry.chancePercent;
            }
        }

        if (validEntries.isEmpty() || totalWeight <= 0) {
            container.setChanged();
            return;
        }

        int size = container.getContainerSize();
        List<Integer> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(i);
        }
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(slots, i, j);
        }

        int minR = Math.max(1, profile.minRolls);
        int maxR = Math.max(minR, profile.maxRolls);
        int rolls = Math.min(size, minR + random.nextInt(maxR - minR + 1));

        for (int r = 0; r < rolls; r++) {
            int pick = random.nextInt(totalWeight);
            int acc = 0;
            int chosenIdx = 0;
            for (int i = 0; i < validEntries.size(); i++) {
                acc += validEntries.get(i).chancePercent;
                if (pick < acc) {
                    chosenIdx = i;
                    break;
                }
            }

            LootEntry entry = validEntries.get(chosenIdx);
            ItemStack stack = validStacks.get(chosenIdx).copy();
            int minC = Math.max(1, entry.minCount);
            int maxC = Math.max(minC, entry.maxCount);
            int count = minC + random.nextInt(maxC - minC + 1);
            stack.setCount(Math.min(stack.getMaxStackSize(), count));

            container.setItem(slots.get(r), stack);
        }

        container.setChanged();
    }

    public static String slugify(String raw) {
        String normalized = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "perfil" : normalized;
    }

    public static String uniqueId(String base, List<LootProfile> existing) {
        String candidate = slugify(base);
        int suffix = 2;
        while (containsId(existing, candidate)) {
            candidate = slugify(base) + "_" + suffix++;
        }
        return candidate;
    }

    private static boolean containsId(List<LootProfile> list, String id) {
        for (LootProfile p : list) {
            if (p.id != null && p.id.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleItemSnbt(String itemId) {
        return "{id:\"" + itemId + "\",count:1}";
    }

    public static List<LootProfile> createDefaultProfiles() {
        List<LootProfile> list = new ArrayList<>();

        list.add(new LootProfile("comun", "Común", 3, 6, List.of(
                new LootEntry(simpleItemSnbt("minecraft:bread"), 50, 2, 5),
                new LootEntry(simpleItemSnbt("minecraft:torch"), 50, 4, 12),
                new LootEntry(simpleItemSnbt("minecraft:arrow"), 40, 4, 10),
                new LootEntry(simpleItemSnbt("minecraft:coal"), 40, 3, 8),
                new LootEntry(simpleItemSnbt("minecraft:iron_ingot"), 25, 1, 4),
                new LootEntry(simpleItemSnbt("minecraft:apple"), 35, 1, 3)
        )));

        list.add(new LootProfile("raro", "Raro", 4, 7, List.of(
                new LootEntry(simpleItemSnbt("minecraft:iron_ingot"), 45, 3, 7),
                new LootEntry(simpleItemSnbt("minecraft:gold_ingot"), 40, 2, 6),
                new LootEntry(simpleItemSnbt("minecraft:emerald"), 35, 1, 4),
                new LootEntry(simpleItemSnbt("minecraft:diamond"), 25, 1, 3),
                new LootEntry(simpleItemSnbt("minecraft:ender_pearl"), 25, 1, 2),
                new LootEntry(simpleItemSnbt("minecraft:golden_apple"), 20, 1, 2)
        )));

        list.add(new LootProfile("legendario", "Legendario", 4, 8, List.of(
                new LootEntry(simpleItemSnbt("minecraft:diamond"), 45, 2, 5),
                new LootEntry(simpleItemSnbt("minecraft:gold_ingot"), 40, 4, 10),
                new LootEntry(simpleItemSnbt("minecraft:golden_apple"), 35, 1, 3),
                new LootEntry(simpleItemSnbt("minecraft:ancient_debris"), 20, 1, 2),
                new LootEntry(simpleItemSnbt("minecraft:netherite_scrap"), 18, 1, 2),
                new LootEntry(simpleItemSnbt("minecraft:experience_bottle"), 40, 3, 8)
        )));

        list.add(new LootProfile("jefe", "Cofre de Jefe", 5, 9, List.of(
                new LootEntry(simpleItemSnbt("minecraft:diamond"), 55, 3, 8),
                new LootEntry(simpleItemSnbt("minecraft:netherite_ingot"), 20, 1, 2),
                new LootEntry(simpleItemSnbt("minecraft:totem_of_undying"), 18, 1, 1),
                new LootEntry(simpleItemSnbt("minecraft:enchanted_golden_apple"), 12, 1, 1),
                new LootEntry(simpleItemSnbt("minecraft:experience_bottle"), 50, 6, 14),
                new LootEntry(simpleItemSnbt("minecraft:emerald"), 45, 4, 10)
        )));

        return list;
    }
}