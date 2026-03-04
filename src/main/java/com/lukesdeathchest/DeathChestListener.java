package com.lukesdeathchest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.Map;

public class DeathChestListener implements Listener {

    private final LukesDeathChest plugin;
    private final DeathChestManager manager;

    private static final Component PREFIX = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("DeathChest", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

    public DeathChestListener(LukesDeathChest plugin, DeathChestManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        String line0 = event.getLine(0);

        if (line0 == null || !line0.equalsIgnoreCase("[deathchest]")) return;

        if (!player.hasPermission("lukesdeathchest.use")) {
            msg(player, Component.text("You don't have permission to create a death chest.", NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        Block signBlock = event.getBlock();
        BlockData blockData = signBlock.getBlockData();

        if (!(blockData instanceof WallSign wallSign)) {
            msg(player, Component.text("The sign must be placed on the ", NamedTextColor.RED)
                    .append(Component.text("side", NamedTextColor.YELLOW))
                    .append(Component.text(" of a chest, not on top.", NamedTextColor.RED)));
            event.setCancelled(true);
            return;
        }

        Block attachedBlock = signBlock.getRelative(wallSign.getFacing().getOppositeFace());
        if (!(attachedBlock.getState() instanceof Chest)) {
            msg(player, Component.text("The death chest sign must be placed on a chest!", NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        if (manager.hasDeathChest(player.getUniqueId())) {
            Location existing = manager.getChestLocation(player.getUniqueId());
            msg(player, Component.text("You already have a death chest at ", NamedTextColor.RED)
                    .append(Component.text(formatLoc(existing), NamedTextColor.YELLOW))
                    .append(Component.text(". Break it first to move it.", NamedTextColor.RED)));
            event.setCancelled(true);
            return;
        }

        manager.registerDeathChest(player.getUniqueId(), attachedBlock.getLocation(), signBlock.getLocation());

        event.line(0, Component.text("Death Chest", NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD));
        event.line(1, Component.text(player.getName(), NamedTextColor.GOLD));
        event.line(2, Component.empty());
        event.line(3, Component.empty());

        msg(player, Component.text("Death chest registered! Your items will be sent here when you die.", NamedTextColor.GREEN));
        msg(player, Component.text("Only you can break this chest and sign.", NamedTextColor.GRAY));

        startParticleTask(player.getUniqueId(), signBlock.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (event.getKeepInventory()) return;

        UUID uuid = player.getUniqueId();

        if (!manager.hasDeathChest(uuid)) {
            manager.setPendingNoChestNotification(uuid);
            return;
        }

        Location chestLoc = manager.getChestLocation(uuid);

        if (chestLoc == null || chestLoc.getWorld() == null || !(chestLoc.getBlock().getState() instanceof Chest chest)) {
            manager.unregisterDeathChest(uuid);
            manager.setPendingNoChestNotification(uuid);
            return;
        }

        Inventory inv = chest.getInventory();

        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();

        int savedLevel = player.getLevel();
        float savedProgress = player.getExp();
        player.setLevel(0);
        player.setExp(0f);
        event.setDroppedExp(0);
        manager.storePendingXp(uuid, savedLevel, savedProgress);

        ItemStack[] dropArray = drops.stream()
                .filter(i -> i != null && i.getType() != Material.AIR)
                .toArray(ItemStack[]::new);

        HashMap<Integer, ItemStack> leftover = inv.addItem(dropArray);

        Location deathLoc = player.getLocation();
        if (!leftover.isEmpty()) {
            for (ItemStack item : leftover.values()) {
                if (item != null && item.getType() != Material.AIR) {
                    Objects.requireNonNull(deathLoc.getWorld()).dropItemNaturally(deathLoc, item);
                }
            }
        }

        manager.setLastUsed(uuid, System.currentTimeMillis());
        manager.setPendingChestFilledNotification(uuid, chestLoc, leftover.size());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!manager.hasPendingNotification(uuid)) return;

        int type = manager.getPendingNotificationType(uuid);
        Location chestLoc = manager.getPendingChestLocation(uuid);
        int leftover = manager.getPendingLeftoverCount(uuid);
        manager.clearPendingNotification(uuid);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            switch (type) {
                case 0 -> {
                    msg(player, Component.text("You died and had no death chest!", NamedTextColor.RED));
                    msg(player, Component.text("Place a sign on a chest with ", NamedTextColor.GRAY)
                            .append(Component.text("[deathchest]", NamedTextColor.YELLOW))
                            .append(Component.text(" on the first line to set one up.", NamedTextColor.GRAY)));
                }
                case 1 -> {
                    msg(player, Component.text("All your items were saved to your death chest.", NamedTextColor.GREEN));
                }
                case 2 -> {
                    msg(player, Component.text("Your death chest was partially full!", NamedTextColor.GOLD));
                    msg(player, Component.text(leftover + " item stack(s) could not fit and dropped at your death location.", NamedTextColor.YELLOW));
                }
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();

        UUID signOwner = manager.getSignOwner(loc);
        if (signOwner != null) {
            if (!isOwnerOrAdmin(player, signOwner)) {
                msg(player, Component.text("You cannot break someone else's death chest sign!", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
            notifyUnregister(player, signOwner);
            manager.stopParticleTask(signOwner);
            manager.unregisterDeathChest(signOwner);
            return;
        }

        UUID chestOwner = manager.getChestOwner(loc);

        if (chestOwner == null && block.getState() instanceof Chest chest) {
            Inventory inv = chest.getInventory();
            if (inv instanceof DoubleChestInventory) {
                DoubleChestInventory dci = (DoubleChestInventory) inv;
                InventoryHolder holder = dci.getHolder();
                if (holder instanceof DoubleChest) {
                    DoubleChest dc = (DoubleChest) holder;
                    if (dc.getLeftSide() instanceof Chest left) {
                        chestOwner = manager.getChestOwner(left.getLocation());
                    }
                    if (chestOwner == null && dc.getRightSide() instanceof Chest right) {
                        chestOwner = manager.getChestOwner(right.getLocation());
                    }
                }
            }
        }

        if (chestOwner != null) {
            if (!isOwnerOrAdmin(player, chestOwner)) {
                msg(player, Component.text("You cannot break someone else's death chest!", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
            Location signLoc = manager.getSignLocation(chestOwner);
            if (signLoc != null) {
                Block signBlock = signLoc.getBlock();
                if (signBlock.getState() instanceof Sign) {
                    signBlock.setType(Material.AIR);
                }
            }
            notifyUnregister(player, chestOwner);
            manager.stopParticleTask(chestOwner);
            manager.unregisterDeathChest(chestOwner);
        }
    }

    @EventHandler
    public void onInventoryOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        if (!manager.hasPendingXp(uuid)) return;

        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        Location chestLoc = manager.getChestLocation(uuid);
        if (chestLoc == null) return;

        boolean isTheirChest = false;
        if (holder instanceof Chest c && c.getLocation().equals(chestLoc)) {
            isTheirChest = true;
        } else if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide() instanceof Chest l && l.getLocation().equals(chestLoc)) isTheirChest = true;
            if (dc.getRightSide() instanceof Chest r && r.getLocation().equals(chestLoc)) isTheirChest = true;
        }

        if (!isTheirChest) {
            return;
        }

        // Give XP back
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int[] xpData = manager.consumePendingXp(uuid);
            if (xpData == null) return;
            player.setLevel(xpData[0]);
            player.setExp(Float.intBitsToFloat(xpData[1]));
            msg(player, Component.text("Your experience has been returned to you.", NamedTextColor.GREEN));
        }, 1L);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Sign)) return;

        Location loc = block.getLocation();
        UUID signOwner = manager.getSignOwner(loc);
        if (signOwner == null) return;

        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(signOwner) && !player.hasPermission("lukesdeathchest.admin")) return;

        Location chestLoc = manager.getChestLocation(signOwner);
        if (chestLoc == null || !(chestLoc.getBlock().getState() instanceof Chest chest)) return;

        Inventory inv = chest.getInventory();
        int used = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) used++;
        }
        int total = inv.getSize();
        int pct = (int) ((used / (double) total) * 100);

        long lastUsed = manager.getLastUsed(signOwner);
        String lastUsedStr = lastUsed == 0 ? "Never" : formatTimeSince(lastUsed);

        msg(player, Component.text("— Death Chest Status —", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        msg(player, Component.text("Capacity: ", NamedTextColor.GRAY)
                .append(Component.text(used + "/" + total + " slots (" + pct + "%)", capacityColor(pct))));
        msg(player, Component.text("Last used: ", NamedTextColor.GRAY)
                .append(Component.text(lastUsedStr, NamedTextColor.YELLOW)));

        event.setCancelled(true);
    }

    private boolean isOwnerOrAdmin(Player player, UUID owner) {
        return player.getUniqueId().equals(owner) || player.hasPermission("lukesdeathchest.admin");
    }

    private void notifyUnregister(Player breaker, UUID owner) {
        if (breaker.getUniqueId().equals(owner)) {
            msg(breaker, Component.text("Your death chest has been unregistered.", NamedTextColor.YELLOW));
        } else {
            msg(breaker, Component.text("Death chest unregistered (admin action).", NamedTextColor.YELLOW));
            Player ownerPlayer = Bukkit.getPlayer(owner);
            if (ownerPlayer != null && ownerPlayer.isOnline()) {
                msg(ownerPlayer, Component.text("An admin removed your death chest registration.", NamedTextColor.RED));
            }
        }
    }

    private void msg(Player player, Component component) {
        player.sendMessage(PREFIX.append(component));
    }

    private String formatLoc(Location loc) {
        if (loc == null) return "unknown";
        return String.format("(%d, %d, %d) in %s", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                loc.getWorld() != null ? loc.getWorld().getName() : "?");
    }

    private NamedTextColor capacityColor(int pct) {
        if (pct < 50) return NamedTextColor.GREEN;
        if (pct < 80) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    public void restartParticlesForAllChests() {
        for (Map.Entry<UUID, DeathChestData> entry : manager.getAllChests().entrySet()) {
            Location signLoc = entry.getValue().getSignLocation();
            if (signLoc != null && signLoc.getWorld() != null) {
                startParticleTask(entry.getKey(), signLoc);
            }
        }
    }

    private String formatTimeSince(long epochMillis) {
        long diff = System.currentTimeMillis() - epochMillis;
        long seconds = diff / 1000;
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }

    private void startParticleTask(UUID uuid, Location signLoc) {
        final double[] angle = {0};

        Location chestLoc = manager.getChestLocation(uuid);
        final Location center = (chestLoc != null ? chestLoc : signLoc).clone().add(0.5, 0.5, 0.5);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (center.getWorld() == null) {
                manager.stopParticleTask(uuid);
                return;
            }

            double r = 0.85;
            double wobble = Math.sin(angle[0] * 2) * 0.1;

            double x1 = Math.cos(angle[0]) * r;
            double z1 = Math.sin(angle[0]) * r;

            Location p1 = center.clone().add(x1, wobble, z1);
            Location p2 = center.clone().add(-x1, -wobble, -z1);

            center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, p1, 1, 0, 0, 0, 0);
            center.getWorld().spawnParticle(Particle.ENCHANT, p2, 1, 0, 0, 0, 0);

            angle[0] += 0.12;
            if (angle[0] > Math.PI * 2) angle[0] -= Math.PI * 2;

        }, 0L, 2L);

        manager.startParticleTask(uuid, task);
    }
}
