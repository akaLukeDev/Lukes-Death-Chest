package com.lukesdeathchest;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DeathChestManager {

    private final LukesDeathChest plugin;

    private final Map<UUID, DeathChestData> playerChests = new HashMap<>();

    private final Map<String, UUID> chestLocationToOwner = new HashMap<>();
    private final Map<String, UUID> signLocationToOwner = new HashMap<>();

    private final Map<UUID, BukkitTask> particleTasks = new HashMap<>();

    private final Map<UUID, Integer> pendingXp = new HashMap<>();

    public void storePendingXp(UUID uuid, int xp) {
        if (xp > 0) pendingXp.put(uuid, xp);
    }

    public int consumePendingXp(UUID uuid) {
        Integer xp = pendingXp.remove(uuid);
        return xp != null ? xp : 0;
    }

    private final Map<UUID, Integer> pendingNotificationType = new HashMap<>();
    private final Map<UUID, Location> pendingChestLocation = new HashMap<>();
    private final Map<UUID, Integer> pendingLeftoverCount = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    public DeathChestManager(LukesDeathChest plugin) {
        this.plugin = plugin;
    }

    public void startParticleTask(UUID uuid, BukkitTask task) {
        stopParticleTask(uuid);
        particleTasks.put(uuid, task);
    }

    public void stopParticleTask(UUID uuid) {
        BukkitTask old = particleTasks.remove(uuid);
        if (old != null) old.cancel();
    }

    public void stopAllParticleTasks() {
        particleTasks.values().forEach(BukkitTask::cancel);
        particleTasks.clear();
    }

    public void registerDeathChest(UUID playerUUID, Location chestLoc, Location signLoc) {

        unregisterDeathChest(playerUUID);

        DeathChestData data = new DeathChestData(chestLoc, signLoc);
        playerChests.put(playerUUID, data);
        chestLocationToOwner.put(locationKey(chestLoc), playerUUID);
        signLocationToOwner.put(locationKey(signLoc), playerUUID);

        saveData();
    }

    public void unregisterDeathChest(UUID playerUUID) {
        DeathChestData data = playerChests.remove(playerUUID);
        if (data != null) {
            chestLocationToOwner.remove(locationKey(data.getChestLocation()));
            signLocationToOwner.remove(locationKey(data.getSignLocation()));
            saveData();
        }
    }

    public boolean hasDeathChest(UUID playerUUID) {
        return playerChests.containsKey(playerUUID);
    }

    public Location getChestLocation(UUID playerUUID) {
        DeathChestData data = playerChests.get(playerUUID);
        return data != null ? data.getChestLocation() : null;
    }

    public Location getSignLocation(UUID playerUUID) {
        DeathChestData data = playerChests.get(playerUUID);
        return data != null ? data.getSignLocation() : null;
    }

    public long getLastUsed(UUID playerUUID) {
        DeathChestData data = playerChests.get(playerUUID);
        return data != null ? data.getLastUsed() : 0;
    }

    public void setLastUsed(UUID playerUUID, long time) {
        DeathChestData data = playerChests.get(playerUUID);
        if (data != null) data.setLastUsed(time);
    }

    public UUID getChestOwner(Location loc) {
        return chestLocationToOwner.get(locationKey(loc));
    }

    public UUID getSignOwner(Location loc) {
        return signLocationToOwner.get(locationKey(loc));
    }

    public Map<UUID, DeathChestData> getAllChests() {
        return playerChests;
    }

    public void setPendingNoChestNotification(UUID uuid) {
        pendingNotificationType.put(uuid, 0);
    }

    public void setPendingChestFilledNotification(UUID uuid, Location chestLoc, int leftover) {
        pendingNotificationType.put(uuid, leftover > 0 ? 2 : 1);
        pendingChestLocation.put(uuid, chestLoc);
        pendingLeftoverCount.put(uuid, leftover);
    }

    public boolean hasPendingNotification(UUID uuid) {
        return pendingNotificationType.containsKey(uuid);
    }

    public int getPendingNotificationType(UUID uuid) {
        return pendingNotificationType.getOrDefault(uuid, -1);
    }

    public Location getPendingChestLocation(UUID uuid) {
        return pendingChestLocation.get(uuid);
    }

    public int getPendingLeftoverCount(UUID uuid) {
        return pendingLeftoverCount.getOrDefault(uuid, 0);
    }

    public void clearPendingNotification(UUID uuid) {
        pendingNotificationType.remove(uuid);
        pendingChestLocation.remove(uuid);
        pendingLeftoverCount.remove(uuid);
    }

    public void loadData() {
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create data.yml: " + e.getMessage());
                return;
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = dataConfig.getConfigurationSection("chests");
        if (section == null) return;

        for (String uuidStr : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ConfigurationSection entry = section.getConfigurationSection(uuidStr);
                if (entry == null) continue;

                Location chestLoc = deserializeLocation(entry.getConfigurationSection("chest"));
                Location signLoc = deserializeLocation(entry.getConfigurationSection("sign"));
                if (chestLoc == null || signLoc == null) continue;

                long lastUsed = entry.getLong("lastUsed", 0);

                DeathChestData data = new DeathChestData(chestLoc, signLoc);
                data.setLastUsed(lastUsed);

                playerChests.put(uuid, data);
                chestLocationToOwner.put(locationKey(chestLoc), uuid);
                signLocationToOwner.put(locationKey(signLoc), uuid);

            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid UUID in data.yml: " + uuidStr);
            }
        }

        plugin.getLogger().info("Loaded " + playerChests.size() + " death chest(s) from data.yml.");
    }

    public void saveData() {
        if (dataFile == null || dataConfig == null) return;

        dataConfig.set("chests", null);

        for (Map.Entry<UUID, DeathChestData> entry : playerChests.entrySet()) {
            String path = "chests." + entry.getKey().toString();
            DeathChestData data = entry.getValue();

            serializeLocation(dataConfig, path + ".chest", data.getChestLocation());
            serializeLocation(dataConfig, path + ".sign", data.getSignLocation());
            dataConfig.set(path + ".lastUsed", data.getLastUsed());
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    private String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void serializeLocation(FileConfiguration config, String path, Location loc) {
        config.set(path + ".world", loc.getWorld().getName());
        config.set(path + ".x", loc.getBlockX());
        config.set(path + ".y", loc.getBlockY());
        config.set(path + ".z", loc.getBlockZ());
    }

    private Location deserializeLocation(ConfigurationSection section) {
        if (section == null) return null;
        String worldName = section.getString("world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World '" + worldName + "' not found for death chest entry — skipping.");
            return null;
        }
        int x = section.getInt("x");
        int y = section.getInt("y");
        int z = section.getInt("z");
        return new Location(world, x, y, z);
    }
}
