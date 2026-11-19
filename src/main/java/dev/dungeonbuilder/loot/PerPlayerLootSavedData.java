package dev.dungeonbuilder.loot;

import dev.dungeonbuilder.loot.LootModels.LootEntry;
import dev.dungeonbuilder.loot.LootModels.LootMode;
import dev.dungeonbuilder.loot.LootModels.LootProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Guarda por mundo y dimensión los cofres con loot individual por jugador. */
public class PerPlayerLootSavedData extends SavedData {
    public static final String DATA_NAME = "dungeonbuilder_per_player_loot";

    public static final SavedData.Factory<PerPlayerLootSavedData> FACTORY = new SavedData.Factory<>(
            PerPlayerLootSavedData::new,
            PerPlayerLootSavedData::load,
            DataFixTypes.LEVEL
    );

    public static final class ChestRecord {
        public LootMode mode = LootMode.ORIGINAL;
        public String profileId = "";
        public LootProfile embeddedProfile;
        public List<CompoundTag> originalItems = new ArrayList<>();
        public final Map<UUID, List<CompoundTag>> playerInventories = new HashMap<>();
    }

    private final Map<Long, ChestRecord> chests = new HashMap<>();

    public boolean hasChest(BlockPos pos) {
        return pos != null && chests.containsKey(pos.asLong());
    }

    public ChestRecord getChest(BlockPos pos) {
        return pos == null ? null : chests.get(pos.asLong());
    }

    public void putChest(BlockPos pos, ChestRecord record) {
        if (pos != null && record != null) {
            chests.put(pos.asLong(), record);
            setDirty();
        }
    }

    public void removeChest(BlockPos pos) {
        if (pos != null && chests.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag chestList = new ListTag();
        for (Map.Entry<Long, ChestRecord> entry : chests.entrySet()) {
            ChestRecord rec = entry.getValue();
            CompoundTag cTag = new CompoundTag();
            cTag.putLong("pos", entry.getKey());
            cTag.putString("mode", rec.mode.name());
            cTag.putString("profileId", rec.profileId == null ? "" : rec.profileId);

            if (rec.embeddedProfile != null) {
                CompoundTag pTag = new CompoundTag();
                pTag.putString("id", rec.embeddedProfile.id == null ? "" : rec.embeddedProfile.id);
                pTag.putString("name", rec.embeddedProfile.name == null ? "" : rec.embeddedProfile.name);
                pTag.putInt("minRolls", Math.max(1, rec.embeddedProfile.minRolls));
                pTag.putInt("maxRolls", Math.max(1, rec.embeddedProfile.maxRolls));
                ListTag eList = new ListTag();
                if (rec.embeddedProfile.entries != null) {
                    for (LootEntry le : rec.embeddedProfile.entries) {
                        CompoundTag eTag = new CompoundTag();
                        eTag.putString("itemSnbt", le.itemSnbt == null ? "" : le.itemSnbt);
                        eTag.putInt("chancePercent", le.chancePercent);
                        eTag.putInt("minCount", le.minCount);
                        eTag.putInt("maxCount", le.maxCount);
                        eList.add(eTag);
                    }
                }
                pTag.put("entries", eList);
                cTag.put("embeddedProfile", pTag);
            }

            ListTag origList = new ListTag();
            for (CompoundTag itemTag : rec.originalItems) {
                origList.add(itemTag.copy());
            }
            cTag.put("originalItems", origList);

            ListTag playersList = new ListTag();
            for (Map.Entry<UUID, List<CompoundTag>> pe : rec.playerInventories.entrySet()) {
                CompoundTag pEntry = new CompoundTag();
                pEntry.putUUID("uuid", pe.getKey());
                ListTag invList = new ListTag();
                for (CompoundTag it : pe.getValue()) {
                    invList.add(it.copy());
                }
                pEntry.put("items", invList);
                playersList.add(pEntry);
            }
            cTag.put("players", playersList);

            chestList.add(cTag);
        }
        tag.put("chests", chestList);
        return tag;
    }

    public static PerPlayerLootSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PerPlayerLootSavedData data = new PerPlayerLootSavedData();
        if (!tag.contains("chests", Tag.TAG_LIST)) {
            return data;
        }
        ListTag chestList = tag.getList("chests", Tag.TAG_COMPOUND);
        for (int i = 0; i < chestList.size(); i++) {
            CompoundTag cTag = chestList.getCompound(i);
            long posLong = cTag.getLong("pos");
            ChestRecord rec = new ChestRecord();
            rec.mode = LootMode.fromString(cTag.getString("mode"));
            rec.profileId = cTag.getString("profileId");

            if (cTag.contains("embeddedProfile", Tag.TAG_COMPOUND)) {
                CompoundTag pTag = cTag.getCompound("embeddedProfile");
                String id = pTag.getString("id");
                String name = pTag.getString("name");
                int minRolls = Math.max(1, pTag.getInt("minRolls"));
                int maxRolls = Math.max(minRolls, pTag.getInt("maxRolls"));
                ListTag eList = pTag.getList("entries", Tag.TAG_COMPOUND);
                List<LootEntry> entries = new ArrayList<>(eList.size());
                for (int j = 0; j < eList.size(); j++) {
                    CompoundTag eTag = eList.getCompound(j);
                    entries.add(new LootEntry(
                            eTag.getString("itemSnbt"),
                            eTag.getInt("chancePercent"),
                            eTag.getInt("minCount"),
                            eTag.getInt("maxCount")
                    ));
                }
                rec.embeddedProfile = new LootProfile(id, name, minRolls, maxRolls, entries);
            }

            ListTag origList = cTag.getList("originalItems", Tag.TAG_COMPOUND);
            for (int j = 0; j < origList.size(); j++) {
                rec.originalItems.add(origList.getCompound(j).copy());
            }

            ListTag playersList = cTag.getList("players", Tag.TAG_COMPOUND);
            for (int j = 0; j < playersList.size(); j++) {
                CompoundTag pEntry = playersList.getCompound(j);
                if (!pEntry.hasUUID("uuid")) {
                    continue;
                }
                UUID uuid = pEntry.getUUID("uuid");
                ListTag invList = pEntry.getList("items", Tag.TAG_COMPOUND);
                List<CompoundTag> savedItems = new ArrayList<>(invList.size());
                for (int k = 0; k < invList.size(); k++) {
                    savedItems.add(invList.getCompound(k).copy());
                }
                rec.playerInventories.put(uuid, savedItems);
            }

            data.chests.put(posLong, rec);
        }
        return data;
    }

    public static List<CompoundTag> extractItemTagsFromBeNbt(CompoundTag beData) {
        List<CompoundTag> result = new ArrayList<>();
        if (beData != null && beData.contains("Items", Tag.TAG_LIST)) {
            ListTag list = beData.getList("Items", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                result.add(list.getCompound(i).copy());
            }
        }
        return result;
    }

    public static List<CompoundTag> serializeContainer(net.minecraft.world.Container container, HolderLookup.Provider registries) {
        List<CompoundTag> out = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                Tag saved = stack.save(registries);
                if (saved instanceof CompoundTag ct) {
                    ct.putByte("Slot", (byte) i);
                    out.add(ct);
                }
            }
        }
        return out;
    }
}
