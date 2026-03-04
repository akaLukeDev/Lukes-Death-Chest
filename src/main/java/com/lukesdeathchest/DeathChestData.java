package com.lukesdeathchest;

import org.bukkit.Location;

public class DeathChestData {

    private final Location chestLocation;
    private final Location signLocation;
    private long lastUsed;

    public DeathChestData(Location chestLocation, Location signLocation) {
        this.chestLocation = chestLocation;
        this.signLocation = signLocation;
        this.lastUsed = 0;
    }

    public Location getChestLocation() {
        return chestLocation;
    }

    public Location getSignLocation() {
        return signLocation;
    }

    public long getLastUsed() {
        return lastUsed;
    }

    public void setLastUsed(long lastUsed) {
        this.lastUsed = lastUsed;
    }
}
