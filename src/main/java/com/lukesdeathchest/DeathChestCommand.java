package com.lukesdeathchest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class DeathChestCommand implements CommandExecutor, TabCompleter {

    private final LukesDeathChest plugin;
    private final DeathChestManager manager;

    private static final Component PREFIX = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("DeathChest", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

    public DeathChestCommand(LukesDeathChest plugin, DeathChestManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "remove" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }
                if (!manager.hasDeathChest(player.getUniqueId())) {
                    msg(player, Component.text("You don't have a death chest to remove.", NamedTextColor.RED));
                    return true;
                }
                Location signLoc = manager.getSignLocation(player.getUniqueId());
                if (signLoc != null && signLoc.getBlock().getState() instanceof org.bukkit.block.Sign) {
                    signLoc.getBlock().setType(org.bukkit.Material.AIR);
                }
                manager.unregisterDeathChest(player.getUniqueId());
                msg(player, Component.text("Your death chest has been unregistered. The chest and its contents remain.", NamedTextColor.YELLOW));
            }

            case "admin" -> {
                if (!sender.hasPermission("lukesdeathchest.admin")) {
                    sender.sendMessage(PREFIX.append(Component.text("You don't have permission.", NamedTextColor.RED)));
                    return true;
                }
                if (args.length < 2) {
                    sendAdminHelp(sender);
                    return true;
                }
                handleAdmin(sender, args);
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (args[1].equalsIgnoreCase("list")) {
            Map<UUID, DeathChestData> all = manager.getAllChests();
            if (all.isEmpty()) {
                sender.sendMessage(PREFIX.append(Component.text("No death chests registered.", NamedTextColor.GRAY)));
                return;
            }
            sender.sendMessage(PREFIX.append(Component.text("Registered death chests (" + all.size() + "):", NamedTextColor.GOLD)));
            for (Map.Entry<UUID, DeathChestData> entry : all.entrySet()) {
                String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                if (name == null) name = entry.getKey().toString();
                Location loc = entry.getValue().getChestLocation();
                sender.sendMessage(Component.text("  • ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(name, NamedTextColor.GOLD))
                        .append(Component.text(" → ", NamedTextColor.GRAY))
                        .append(Component.text(formatLoc(loc), NamedTextColor.YELLOW)));
            }
            return;
        }

        if (args.length < 3) {
            sendAdminHelp(sender);
            return;
        }

        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(PREFIX.append(Component.text("Player not found: " + targetName, NamedTextColor.RED)));
            return;
        }

        UUID targetUUID = target.getUniqueId();

        switch (args[1].toLowerCase()) {
            case "info" -> {
                if (!manager.hasDeathChest(targetUUID)) {
                    sender.sendMessage(PREFIX.append(Component.text(targetName + " has no death chest.", NamedTextColor.GRAY)));
                    return;
                }
                Location chestLoc = manager.getChestLocation(targetUUID);
                Location signLoc = manager.getSignLocation(targetUUID);
                long lastUsed = manager.getLastUsed(targetUUID);
                sender.sendMessage(PREFIX.append(Component.text("Death chest info for " + targetName + ":", NamedTextColor.GOLD)));
                sender.sendMessage(Component.text("  Chest: ", NamedTextColor.GRAY).append(Component.text(formatLoc(chestLoc), NamedTextColor.YELLOW)));
                sender.sendMessage(Component.text("  Sign:  ", NamedTextColor.GRAY).append(Component.text(formatLoc(signLoc), NamedTextColor.YELLOW)));
                sender.sendMessage(Component.text("  Last used: ", NamedTextColor.GRAY)
                        .append(Component.text(lastUsed == 0 ? "Never" : new Date(lastUsed).toString(), NamedTextColor.YELLOW)));
            }
            case "remove" -> {
                if (!manager.hasDeathChest(targetUUID)) {
                    sender.sendMessage(PREFIX.append(Component.text(targetName + " has no death chest.", NamedTextColor.GRAY)));
                    return;
                }
                Location signLoc = manager.getSignLocation(targetUUID);
                if (signLoc != null && signLoc.getBlock().getState() instanceof org.bukkit.block.Sign) {
                    signLoc.getBlock().setType(org.bukkit.Material.AIR);
                }
                manager.unregisterDeathChest(targetUUID);
                sender.sendMessage(PREFIX.append(Component.text("Removed " + targetName + "'s death chest registration.", NamedTextColor.YELLOW)));

                Player online = Bukkit.getPlayer(targetUUID);
                if (online != null) {
                    msg(online, Component.text("An admin removed your death chest registration.", NamedTextColor.RED));
                }
            }
            default -> sendAdminHelp(sender);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("remove"));
            if (sender.hasPermission("lukesdeathchest.admin")) subs.add("admin");
            return filterStart(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("lukesdeathchest.admin")) {
            return filterStart(List.of("list", "info", "remove"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && (args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("remove"))
                && sender.hasPermission("lukesdeathchest.admin")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filterStart(names, args[2]);
        }
        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX.append(Component.text("Commands:", NamedTextColor.GOLD)));
        sender.sendMessage(Component.text("  /ldc remove", NamedTextColor.YELLOW)
                .append(Component.text(" — Unregister your death chest (chest stays, items safe).", NamedTextColor.GRAY)));
        if (sender.hasPermission("lukesdeathchest.admin")) {
            sender.sendMessage(Component.text("  /ldc admin list|info|remove [player]", NamedTextColor.YELLOW)
                    .append(Component.text(" — Admin management.", NamedTextColor.GRAY)));
        }
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(PREFIX.append(Component.text("Admin commands:", NamedTextColor.GOLD)));
        sender.sendMessage(Component.text("  /ldc admin list", NamedTextColor.YELLOW)
                .append(Component.text(" — List all registered death chests.", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /ldc admin info <player>", NamedTextColor.YELLOW)
                .append(Component.text(" — View a player's death chest info.", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /ldc admin remove <player>", NamedTextColor.YELLOW)
                .append(Component.text(" — Remove a player's death chest registration.", NamedTextColor.GRAY)));
    }

    private void msg(Player player, Component component) {
        player.sendMessage(PREFIX.append(component));
    }

    private String formatLoc(Location loc) {
        if (loc == null) return "unknown";
        return String.format("(%d, %d, %d) in %s",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                loc.getWorld() != null ? loc.getWorld().getName() : "?");
    }

    private List<String> filterStart(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) result.add(s);
        }
        return result;
    }
}
