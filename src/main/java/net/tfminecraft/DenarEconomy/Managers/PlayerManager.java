package net.tfminecraft.DenarEconomy.Managers;

import java.util.HashMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.DenarEconomy.Data.PlayerData;
import net.tfminecraft.DenarEconomy.Database.Database;

public class PlayerManager implements Listener{
	private HashMap<UUID, PlayerData> data = new HashMap<>();
	
	public boolean exists(Player p) {
		return data.containsKey(p.getUniqueId());
	}

	public boolean exists(UUID id) {
		return data.containsKey(id);
	}
	
	public PlayerData get(Player p) {
		return get(p.getUniqueId());
	}

	public PlayerData get(UUID id) {
		if(!exists(id)) add(id);
		return data.get(id);
	}
	
	public void add(UUID id) {
		if(exists(id)) return;
		if(Database.hasPlayerData(id)) data.put(id, Database.loadPlayerData(id));
		else data.put(id, new PlayerData(id));
	}
	
	public void init(Player p) {
		if(exists(p)) return;
		add(p.getUniqueId());
	}
	
	public void start() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			init(p);
		}
	}

	public void save(UUID id){
		if(!exists(id)) return;
		Database.savePlayerData(get(id));
		data.remove(id);
	}
	
	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		init(p);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e){
		Player p = e.getPlayer();
		save(p.getUniqueId());
	}
}
