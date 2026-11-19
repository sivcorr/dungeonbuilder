package dev.dungeonbuilder.permissions;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.dungeonbuilder.permissions.PermissionsManager.Role;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/** Comando administrativo opcional para asignar roles o recargar permissions.json en vivo. */
public final class PermissionsCommand {
    private PermissionsCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("dungeonbuilder")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    PermissionsManager.reload();
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("DungeonBuilder » permissions.json recargado.")
                                                    .withStyle(ChatFormatting.GREEN), true);
                                    return 1;
                                }))
                        .then(Commands.literal("role")
                                .then(Commands.argument("jugador", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                ctx.getSource().getServer().getPlayerNames(), builder))
                                        .executes(ctx -> {
                                            String target = StringArgumentType.getString(ctx, "jugador");
                                            ServerPlayer online = ctx.getSource().getServer()
                                                    .getPlayerList().getPlayerByName(target);
                                            Role role = online != null
                                                    ? PermissionsManager.getRole(online)
                                                    : PermissionsManager.getAssignedRoleByKey(target);
                                            ctx.getSource().sendSuccess(() ->
                                                    Component.literal("DungeonBuilder » Rol de " + target + ": ")
                                                            .withStyle(ChatFormatting.AQUA)
                                                            .append(Component.literal(role.name())
                                                                    .withStyle(ChatFormatting.YELLOW)), false);
                                            return 1;
                                        })
                                        .then(Commands.argument("rol", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        new String[]{"player", "builder", "admin"}, builder))
                                                .executes(ctx -> {
                                                    String target = StringArgumentType.getString(ctx, "jugador");
                                                    String rawRole = StringArgumentType.getString(ctx, "rol")
                                                            .toUpperCase(Locale.ROOT);
                                                    Role role;
                                                    try {
                                                        role = Role.valueOf(rawRole);
                                                    } catch (IllegalArgumentException e) {
                                                        ctx.getSource().sendFailure(Component.literal(
                                                                "Rol inválido. Usa: player, builder o admin."));
                                                        return 0;
                                                    }

                                                    try {
                                                        PermissionsManager.setPlayerRole(target, role);
                                                        ctx.getSource().sendSuccess(() ->
                                                                Component.literal("DungeonBuilder » ")
                                                                        .withStyle(ChatFormatting.GREEN)
                                                                        .append(Component.literal(target + " ahora tiene el rol " + role.name())
                                                                                .withStyle(ChatFormatting.WHITE)), true);

                                                        ServerPlayer online = ctx.getSource().getServer()
                                                                .getPlayerList().getPlayerByName(target);
                                                        if (online != null) {
                                                            online.displayClientMessage(
                                                                    Component.literal("DungeonBuilder » ")
                                                                            .withStyle(ChatFormatting.AQUA)
                                                                            .append(Component.literal("Tu rol en el servidor ahora es: " + role.name())
                                                                                    .withStyle(ChatFormatting.YELLOW)),
                                                                    false
                                                            );
                                                        }
                                                        return 1;
                                                    } catch (Exception e) {
                                                        ctx.getSource().sendFailure(Component.literal(
                                                                "Error guardando permissions.json: " + e.getMessage()));
                                                        return 0;
                                                    }
                                                }))))));
    }
}