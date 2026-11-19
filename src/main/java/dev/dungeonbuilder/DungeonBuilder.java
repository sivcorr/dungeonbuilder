package dev.dungeonbuilder;

import dev.dungeonbuilder.item.ModItems;
import dev.dungeonbuilder.loot.PerPlayerLootManager;
import dev.dungeonbuilder.network.ModNetworking;
import dev.dungeonbuilder.permissions.PermissionsCommand;
import dev.dungeonbuilder.permissions.PermissionsManager;
import dev.dungeonbuilder.placement.PlacementManager;
import dev.dungeonbuilder.selection.SelectionManager;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DungeonBuilder implements ModInitializer {
    public static final String MOD_ID = "dungeonbuilder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModItems.register();
        PermissionsManager.init();
        PermissionsCommand.register();
        ModNetworking.registerPayloads();
        ModNetworking.registerServerReceivers();
        SelectionManager.register();
        PlacementManager.register();
        PerPlayerLootManager.register();
        LOGGER.info("DungeonBuilder inicializado: objetos, permisos, red, seleccionador, colocación y cofres por jugador registrados.");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}