package com.lukesdeathchest;

import org.bukkit.plugin.java.JavaPlugin;

public class LukesDeathChest extends JavaPlugin {

    private DeathChestManager manager;

    @Override
    public void onEnable() {
        manager = new DeathChestManager(this);
        manager.loadData();

        DeathChestListener listener = new DeathChestListener(this, manager);
        getServer().getPluginManager().registerEvents(listener, this);
        listener.restartParticlesForAllChests();

        DeathChestCommand commandExecutor = new DeathChestCommand(this, manager);
        getCommand("ldc").setExecutor(commandExecutor);
        getCommand("ldc").setTabCompleter(commandExecutor);

        getLogger().info("Luke's Death Chest v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stopAllParticleTasks();
            manager.saveData();
        }
        getLogger().info("Luke's Death Chest disabled. Data saved.");
    }
}
