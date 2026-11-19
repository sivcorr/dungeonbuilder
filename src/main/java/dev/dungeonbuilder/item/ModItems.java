package dev.dungeonbuilder.item;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.dungeonbuilder.DungeonBuilder;
/** Registro de los objetos del mod y de su pestaña en el modo creativo. */
public final class ModItems {
    private ModItems() {}

    public static final Item STRUCTURE_LIBRARY = register("structure_library",
            new StructureLibraryItem(new Item.Properties().stacksTo(1)));

    /** Herramienta independiente para marcar las dos esquinas de una estructura. */
    public static final Item STRUCTURE_SELECTOR = register("structure_selector",
            new StructureSelectorItem(new Item.Properties().stacksTo(1)));

    /** Abre el flujo de exportación de la selección actual. */
    public static final Item STRUCTURE_EXPORTER = register("structure_exporter",
            new StructureExporterItem(new Item.Properties().stacksTo(1)));

    public static final Item PERSONAL_LIBRARY = register("personal_library",
            new PersonalLibraryItem(new Item.Properties().stacksTo(1)));

    public static final ResourceKey<CreativeModeTab> TAB_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, DungeonBuilder.id("main"));

    private static <T extends Item> T register(String name, T item) {
        return Registry.register(BuiltInRegistries.ITEM, DungeonBuilder.id(name), item);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_KEY, FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.dungeonbuilder.main"))
                .icon(() -> new ItemStack(STRUCTURE_EXPORTER))
                .displayItems((parameters, output) -> {
                    output.accept(STRUCTURE_LIBRARY);
                    output.accept(STRUCTURE_SELECTOR);
                    output.accept(STRUCTURE_EXPORTER);
                    output.accept(PERSONAL_LIBRARY);
                })
                .build());
        DungeonBuilder.LOGGER.info("Objetos de DungeonBuilder registrados");
    }
}
