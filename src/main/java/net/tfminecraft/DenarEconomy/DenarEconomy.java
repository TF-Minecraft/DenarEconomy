package net.tfminecraft.DenarEconomy;

import java.io.File;

import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.DenarEconomy.Database.Database;
import net.tfminecraft.DenarEconomy.Loaders.CoinLoader;
import net.tfminecraft.DenarEconomy.Loaders.DropLoader;
import net.tfminecraft.DenarEconomy.Loaders.MessageLoader;
import net.tfminecraft.DenarEconomy.Managers.CommandManager;
import net.tfminecraft.DenarEconomy.Managers.MoneyManager;
import net.tfminecraft.DenarEconomy.Managers.PlayerManager;

public class DenarEconomy extends JavaPlugin {
	
	public static DenarEconomy plugin;
	
	private final CommandManager commands = new CommandManager();
	
	private final CoinLoader coinLoader = new CoinLoader();
	private final DropLoader dropLoader = new DropLoader();
	private final MessageLoader messageLoader = new MessageLoader();
	
	private static final PlayerManager playerManager = new PlayerManager();
	private static final MoneyManager moneyManager = new MoneyManager();
	
	@Override
	public void onEnable() {
		plugin = this;
		createFolders();
		createConfigs();
		loadConfigs();
		registerListeners();
		getCommand(commands.cmd1).setExecutor(commands);
		getCommand(commands.cmd2).setExecutor(commands);
		getCommand(commands.cmd1).setTabCompleter(commands);
		getCommand(commands.cmd2).setTabCompleter(commands);
		playerManager.start();
	}

	@Override
	public void onDisable(){
		for(Player p : Bukkit.getOnlinePlayers()){
			Database.savePlayerData(playerManager.get(p));
		}
		for (ArmorStand stand : moneyManager.getStandMap().values()) {
			if (stand != null && !stand.isDead()) stand.remove();
		}
		moneyManager.getStandMap().clear();

		for (int id : moneyManager.getTaskMap().values()) {
			Bukkit.getScheduler().cancelTask(id);
		}
		moneyManager.getTaskMap().clear();
	}

	
	public void loadConfigs() {
		messageLoader.load(new File(getDataFolder(), "messages.yml"));
		coinLoader.loadCoins(new File(getDataFolder(), "coins.yml"));
		dropLoader.load(new File(getDataFolder(), "drops.yml"));
	}
	
	public void registerListeners() {
		getServer().getPluginManager().registerEvents(playerManager, this);
		getServer().getPluginManager().registerEvents(moneyManager, this);
	}
	public void createFolders() {
		if (!getDataFolder().exists()) getDataFolder().mkdir();
		File subFolder = new File(getDataFolder(), "Data");
		if(!subFolder.exists()) subFolder.mkdir();
		subFolder = new File(getDataFolder(), "PlayerData");
		if(!subFolder.exists()) subFolder.mkdir();
	}
	public void createConfigs() {
		String[] files = {
				"coins.yml",
				"drops.yml",
				"messages.yml"
				};
		for(String s : files) {
			File newConfigFile = new File(getDataFolder(), s);
	        if (!newConfigFile.exists()) {
	        	newConfigFile.getParentFile().mkdirs();
	            saveResource(s, false);
	        }
		}
	}
	
	public static PlayerManager getPlayerManager() {
		return playerManager;
	}
	public static MoneyManager getMoneyManager() {
		return moneyManager;
	}
}
