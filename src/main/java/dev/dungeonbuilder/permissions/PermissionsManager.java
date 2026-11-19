package dev.dungeonbuilder.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dungeonbuilder.DungeonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Gestiona roles y permisos del servidor en DungeonBuilder/server/config/permissions.json. */
public final class PermissionsManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Role {
        PLAYER,
        BUILDER,
        ADMIN
    }

    public record RolePerms(
            boolean view,
            boolean preview,
            boolean place,
            boolean export,
            boolean delete,
            boolean placeLocal,
            boolean editServerLoot
    ) {
        public static RolePerms from(RoleConfig rc) {
            if (rc == null) {
                return new RolePerms(true, true, true, false, false, false, false);
            }
            return new RolePerms(
                    rc.view, rc.preview, rc.place, rc.export, rc.delete, rc.placeLocal, rc.editServerLoot
            );
        }

        public RoleConfig toRoleConfig() {
            return new RoleConfig(view, preview, place, export, delete, placeLocal, editServerLoot);
        }
    }

    public record PlayerRoleInfo(
            String name,
            String assignedRole,
            String effectiveRole,
            boolean online,
            boolean forcedOpAdmin
    ) {}

    public record PlayerAssignment(String name, String role) {}

    public record PermissionsSnapshot(
            String defaultRole,
            RolePerms playerPerms,
            RolePerms builderPerms,
            RolePerms adminPerms,
            List<PlayerRoleInfo> players
    ) {}

    public static final class RoleConfig {
        public boolean view;
        public boolean preview;
        public boolean place;
        public boolean export;
        public boolean delete;
        public boolean placeLocal;
        public boolean editServerLoot;

        public RoleConfig() {}

        public RoleConfig(boolean view, boolean preview, boolean place,
                          boolean export, boolean delete, boolean placeLocal, boolean editServerLoot) {
            this.view = view;
            this.preview = preview;
            this.place = place;
            this.export = export;
            this.delete = delete;
            this.placeLocal = placeLocal;
            this.editServerLoot = editServerLoot;
        }
    }

    public static final class PermissionsConfig {
        public String defaultRole = "PLAYER";
        public int opLevelForAdmin = 2;
        public Map<String, RoleConfig> roles = new LinkedHashMap<>();
        public Map<String, String> players = new LinkedHashMap<>();
    }

    private static PermissionsConfig config;
    private static long lastLoadedModifiedMs = -1L;

    private PermissionsManager() {}

    public static Path configFile() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("DungeonBuilder")
                .resolve("server")
                .resolve("config")
                .resolve("permissions.json");
    }

    public static synchronized void init() {
        reload();
    }

    public static synchronized void reload() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                config = createDefaultConfig();
                writeConfig(file, config);
                lastLoadedModifiedMs = Files.getLastModifiedTime(file).toMillis();
                return;
            }

            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                PermissionsConfig loaded = GSON.fromJson(reader, PermissionsConfig.class);
                config = sanitizeConfig(loaded);
                lastLoadedModifiedMs = Files.getLastModifiedTime(file).toMillis();
            }
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error cargando permissions.json, usando valores por defecto.", e);
            if (config == null) {
                config = createDefaultConfig();
            }
        }
    }

    private static synchronized void ensureFresh() {
        if (config == null) {
            reload();
            return;
        }
        Path file = configFile();
        try {
            if (!Files.exists(file)) {
                reload();
                return;
            }
            long currentMtime = Files.getLastModifiedTime(file).toMillis();
            if (currentMtime != lastLoadedModifiedMs) {
                reload();
            }
        } catch (IOException ignored) {
        }
    }

    public static synchronized boolean isForcedOpAdmin(ServerPlayer player) {
        ensureFresh();
        return player.server.isSingleplayerOwner(player.getGameProfile())
                || player.hasPermissions(config.opLevelForAdmin);
    }

    public static synchronized boolean isAdmin(ServerPlayer player) {
        return getRole(player) == Role.ADMIN;
    }

    public static synchronized Role getRole(ServerPlayer player) {
        ensureFresh();

        if (isForcedOpAdmin(player)) {
            return Role.ADMIN;
        }

        String uuidStr = player.getUUID().toString();
        String name = player.getScoreboardName();

        String assigned = findAssignedRoleString(uuidStr, name);
        if (assigned != null) {
            return parseRole(assigned, Role.PLAYER);
        }

        return parseRole(config.defaultRole, Role.PLAYER);
    }

    public static synchronized RoleConfig getPermissions(ServerPlayer player) {
        Role role = getRole(player);
        RoleConfig rc = config.roles.get(role.name());
        if (rc != null) {
            return rc;
        }
        return defaultRoleConfig(role);
    }

    public static boolean canView(ServerPlayer player) {
        return getPermissions(player).view;
    }

    public static boolean canPreview(ServerPlayer player) {
        return getPermissions(player).preview;
    }

    public static boolean canPlaceServer(ServerPlayer player) {
        return getPermissions(player).place;
    }

    public static boolean canExport(ServerPlayer player) {
        return getPermissions(player).export;
    }

    public static boolean canDeleteServer(ServerPlayer player) {
        return getPermissions(player).delete;
    }

    public static boolean canPlaceLocal(ServerPlayer player) {
        return getPermissions(player).placeLocal;
    }

    public static boolean canEditServerLoot(ServerPlayer player) {
        return getPermissions(player).editServerLoot;
    }

    public static synchronized PermissionsSnapshot buildSnapshot(MinecraftServer server) {
        ensureFresh();

        String defaultRole = parseRole(config.defaultRole, Role.PLAYER) == Role.BUILDER ? "BUILDER" : "PLAYER";
        RolePerms playerPerms = RolePerms.from(config.roles.getOrDefault(Role.PLAYER.name(), defaultRoleConfig(Role.PLAYER)));
        RolePerms builderPerms = RolePerms.from(config.roles.getOrDefault(Role.BUILDER.name(), defaultRoleConfig(Role.BUILDER)));
        RolePerms adminPerms = new RolePerms(true, true, true, true, true, true, true);

        Map<String, PlayerRoleInfo> byKey = new LinkedHashMap<>();

        if (server != null) {
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                String name = online.getScoreboardName();
                String assigned = findAssignedRoleString(online.getUUID().toString(), name);
                String cleanAssigned = assigned == null ? "" : parseRole(assigned, Role.PLAYER).name();
                String effective = getRole(online).name();
                boolean forcedOp = isForcedOpAdmin(online);
                byKey.put(name.toLowerCase(Locale.ROOT), new PlayerRoleInfo(
                        name, cleanAssigned, effective, true, forcedOp
                ));
            }
        }

        if (config.players != null) {
            for (Map.Entry<String, String> entry : config.players.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                String lower = key.trim().toLowerCase(Locale.ROOT);
                if (byKey.containsKey(lower)) {
                    continue;
                }
                Role r = parseRole(entry.getValue(), Role.PLAYER);
                byKey.put(lower, new PlayerRoleInfo(
                        key.trim(), r.name(), r.name(), false, false
                ));
            }
        }

        List<PlayerRoleInfo> list = new ArrayList<>(byKey.values());
        list.sort(Comparator
                .comparing((PlayerRoleInfo p) -> !p.online())
                .thenComparing(p -> p.name().toLowerCase(Locale.ROOT)));

        return new PermissionsSnapshot(defaultRole, playerPerms, builderPerms, adminPerms, list);
    }

    public static synchronized void applyFromGui(
            String defaultRoleRaw,
            RolePerms playerPerms,
            RolePerms builderPerms,
            List<PlayerAssignment> assignments
    ) throws IOException {
        ensureFresh();

        Role defRole = parseRole(defaultRoleRaw, Role.PLAYER);
        if (defRole == Role.ADMIN) {
            defRole = Role.PLAYER;
        }
        config.defaultRole = defRole.name();

        config.roles.put(Role.PLAYER.name(), playerPerms.toRoleConfig());
        config.roles.put(Role.BUILDER.name(), builderPerms.toRoleConfig());
        config.roles.put(Role.ADMIN.name(), defaultRoleConfig(Role.ADMIN));

        Map<String, String> newPlayers = new LinkedHashMap<>();
        if (assignments != null) {
            for (PlayerAssignment a : assignments) {
                if (a == null || a.name() == null || a.name().isBlank() || a.role() == null || a.role().isBlank()) {
                    continue;
                }
                String cleanName = a.name().trim();
                Role role = parseRole(a.role(), Role.PLAYER);
                newPlayers.entrySet().removeIf(e -> e.getKey().equalsIgnoreCase(cleanName));
                newPlayers.put(cleanName, role.name());
            }
        }
        config.players = newPlayers;

        Path file = configFile();
        Files.createDirectories(file.getParent());
        writeConfig(file, config);
        lastLoadedModifiedMs = Files.getLastModifiedTime(file).toMillis();
    }

    public static synchronized void setPlayerRole(String playerNameOrUuid, Role role) throws IOException {
        ensureFresh();
        String targetKey = playerNameOrUuid.trim();
        config.players.entrySet().removeIf(e -> e.getKey().equalsIgnoreCase(targetKey));
        config.players.put(targetKey, role.name());

        Path file = configFile();
        Files.createDirectories(file.getParent());
        writeConfig(file, config);
        lastLoadedModifiedMs = Files.getLastModifiedTime(file).toMillis();
    }

    public static synchronized Role getAssignedRoleByKey(String playerNameOrUuid) {
        ensureFresh();
        String raw = findAssignedRoleString(playerNameOrUuid, playerNameOrUuid);
        if (raw != null) {
            return parseRole(raw, Role.PLAYER);
        }
        return parseRole(config.defaultRole, Role.PLAYER);
    }

    private static String findAssignedRoleString(String uuidStr, String name) {
        if (config.players == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : config.players.entrySet()) {
            String key = entry.getKey();
            if (key.equalsIgnoreCase(uuidStr) || key.equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Role parseRole(String raw, Role fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static PermissionsConfig createDefaultConfig() {
        PermissionsConfig def = new PermissionsConfig();
        def.defaultRole = Role.PLAYER.name();
        def.opLevelForAdmin = 2;
        def.roles.put(Role.PLAYER.name(), defaultRoleConfig(Role.PLAYER));
        def.roles.put(Role.BUILDER.name(), defaultRoleConfig(Role.BUILDER));
        def.roles.put(Role.ADMIN.name(), defaultRoleConfig(Role.ADMIN));
        return def;
    }

    private static PermissionsConfig sanitizeConfig(PermissionsConfig loaded) {
        if (loaded == null) {
            return createDefaultConfig();
        }
        if (loaded.opLevelForAdmin < 1 || loaded.opLevelForAdmin > 4) {
            loaded.opLevelForAdmin = 2;
        }
        if (loaded.defaultRole == null || loaded.defaultRole.isBlank()) {
            loaded.defaultRole = Role.PLAYER.name();
        }
        if (loaded.roles == null) {
            loaded.roles = new LinkedHashMap<>();
        }
        for (Role r : Role.values()) {
            loaded.roles.putIfAbsent(r.name(), defaultRoleConfig(r));
        }
        if (loaded.players == null) {
            loaded.players = new LinkedHashMap<>();
        }
        return loaded;
    }

    private static RoleConfig defaultRoleConfig(Role role) {
        return switch (role) {
            case PLAYER -> new RoleConfig(true, true, true, false, false, false, false);
            case BUILDER -> new RoleConfig(true, true, true, true, false, true, false);
            case ADMIN -> new RoleConfig(true, true, true, true, true, true, true);
        };
    }

    private static void writeConfig(Path file, PermissionsConfig cfg) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(cfg, writer);
        }
    }
}