package dev.dungeonbuilder.placement;

/** Estado en el cliente de si el jugador tiene una colocación disponible para deshacer. */
public final class ClientUndoState {
    private static boolean available = false;
    private static String structureName = "";

    private ClientUndoState() {}

    public static void set(boolean isAvailable, String name) {
        available = isAvailable && name != null && !name.isBlank();
        structureName = available ? name : "";
    }

    public static void clear() {
        available = false;
        structureName = "";
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String structureName() {
        return structureName;
    }
}