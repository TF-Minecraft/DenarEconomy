package net.tfminecraft.DenarEconomy.Loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.DenarEconomy.Item.Coin;

public class CoinLoader {
	public static List<Coin> coins = new ArrayList<Coin>();
	public static List<Coin> get(){
		return coins;
	}
	public static List<Coin> getSortedCoins(){
		Collections.sort(coins, new Comparator<Coin>() {
		    @Override
		    public int compare(Coin c1, Coin c2) {
		        return Double.compare(c1.getValue(), c2.getValue());
		    }
		});
		Collections.reverse(coins);
		return coins;
	}
	public static Coin getByString(String id) {
		for(Coin c : coins) {
			if(c.getId().equalsIgnoreCase(id)) return c;
		}
		return null;
	}
	public static Coin getWithdrawableByValue(double value) {
		long cents = Math.round(value * 100.0);
		for (Coin c : coins) {
			if (!c.canWithdraw() || c.getValue() == null) continue;
			if (Math.round(c.getValue() * 100.0) == cents) return c;
		}
		return null;
	}

	public void loadCoins(File configFile) {
		coins.clear();
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }
		Set<String> set = config.getKeys(false);

		List<String> list = new ArrayList<String>(set);
		
		for(String key : list) {
			Coin c = new Coin(key, config.getConfigurationSection(key));
			coins.add(c);
		}
	}
}
