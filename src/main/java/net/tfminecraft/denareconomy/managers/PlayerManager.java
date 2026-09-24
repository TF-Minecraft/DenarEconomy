package net.tfminecraft.denareconomy.managers;

import java.util.HashMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.Database;

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

	/** Session copy only. Does not load an offline player into the session. */
	public PlayerData peek(UUID id) {
		return data.get(id);
	}

	public void keep(PlayerData playerData) {
		if (playerData == null || playerData.getId() == null) {
			return;
		}
		data.put(playerData.getId(), playerData);
	}

	/** Forget a session copy without writing it. The caller has already saved. */
	public void drop(UUID id) {
		if (id != null) {
			data.remove(id);
		}
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
		OfflineModifier.remember(p.getName(), p.getUniqueId());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e){
		Player p = e.getPlayer();
		save(p.getUniqueId());
	}
}
