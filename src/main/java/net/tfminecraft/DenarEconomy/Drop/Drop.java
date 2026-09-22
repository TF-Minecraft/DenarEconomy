package net.tfminecraft.DenarEconomy.Drop;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

public class Drop {
    private String id;
    private Material block;
    private double chance;
    private double min;
    private double max;

    public Drop(String key, ConfigurationSection config) {
        id = key;
        try {
            block = Material.valueOf(key.toUpperCase());
        } catch (Exception e) {
            block = Material.BEDROCK;
        }
        chance = config.getDouble("chance", 0.5);
        String amount = config.getString("amount", "0.3-0.5");
        min = Double.parseDouble(amount.split("\\-")[0]);
        max = Double.parseDouble(amount.split("\\-")[1]);
    }

    public String getId() {
        return this.id;
    }

    public Material getBlock() {
        return this.block;
    }

    public double getChance() {
        return this.chance;
    }

    public double getAmount() {
        double value = ThreadLocalRandom.current().nextDouble(min, max);
        return Math.round(value * 100.0) / 100.0;
    }
}
