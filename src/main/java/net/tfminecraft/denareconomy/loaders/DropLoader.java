package net.tfminecraft.denareconomy.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.denareconomy.drop.Drop;

public class DropLoader {
	public static HashMap<Material, Drop> drops = new HashMap<>();
	public static double fortuneStrength = 0.33;
	public static HashMap<Material, Drop> get(){
		return drops;
	}
	public static Drop getByBlock(Material id) {
		if(!drops.containsKey(id)) return null;
		return drops.get(id);
	}
	public void load(File configFile) {
		drops.clear();
		fortuneStrength = 0.33;
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }
		Set<String> set = config.getKeys(false);

		List<String> list = new ArrayList<String>(set);
		
		fortuneStrength = config.getDouble("fortune-strength", 0.33);
		for(String key : list) {
			if(key.equalsIgnoreCase("fortune-strength")) continue;
			ConfigurationSection section = config.getConfigurationSection(key);
			if(section == null) continue;
			Drop c = new Drop(key, section);
			drops.put(c.getBlock(), c);
		}
	}
}