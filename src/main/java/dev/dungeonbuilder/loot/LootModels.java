package dev.dungeonbuilder.loot;

import java.util.ArrayList;
import java.util.List;

/** Modelos de datos para la configuración de cofres y perfiles de loot. */
public final class LootModels {
    private LootModels() {}

    public enum LootMode {
        ORIGINAL,
        RANDOM_LOOT;

        public static LootMode fromString(String raw) {
            if ("RANDOM_LOOT".equalsIgnoreCase(raw)) {
                return RANDOM_LOOT;
            }
            return ORIGINAL;
        }
    }

    public record ChestLootConfig(int x, int y, int z, LootMode mode, String profileId, boolean perPlayer) {}

    public static final class LootEntry {
        public String itemSnbt;
        public int chancePercent;
        public int minCount;
        public int maxCount;

        public LootEntry() {}

        public LootEntry(String itemSnbt, int chancePercent, int minCount, int maxCount) {
            this.itemSnbt = itemSnbt;
            this.chancePercent = Math.max(1, Math.min(100, chancePercent));
            this.minCount = Math.max(1, minCount);
            this.maxCount = Math.max(this.minCount, maxCount);
        }

        public LootEntry copy() {
            return new LootEntry(itemSnbt, chancePercent, minCount, maxCount);
        }
    }

    public static final class LootProfile {
        public String id;
        public String name;
        public int minRolls;
        public int maxRolls;
        public List<LootEntry> entries = new ArrayList<>();

        public LootProfile() {}

        public LootProfile(String id, String name, int minRolls, int maxRolls, List<LootEntry> entries) {
            this.id = id;
            this.name = name;
            this.minRolls = Math.max(1, minRolls);
            this.maxRolls = Math.max(this.minRolls, maxRolls);
            this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }

        public LootProfile copy() {
            List<LootEntry> cloned = new ArrayList<>(entries.size());
            for (LootEntry e : entries) {
                cloned.add(e.copy());
            }
            return new LootProfile(id, name, minRolls, maxRolls, cloned);
        }
    }
}